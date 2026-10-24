import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { generateKeyPairSync, createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';

export const dir = path.dirname(fileURLToPath(import.meta.url));
export const repo = path.resolve(dir, '../..');
export const generated = path.join(dir, '.generated');
export const project = 'checkout-payment-bench';
export const compose = path.join(generated, 'compose.json');
export function docker(args, options = {}) {
  return execFileSync('docker', args, { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024, ...options });
}
export function dc(args, options = {}) {
  return docker(['compose', '-p', project, '-f', compose, ...args], options);
}
export function sql(database, query) {
  if (!['product_service','user_service','inventory_service','promotion_service','payment_service','order_service'].includes(database)) throw Error('bench DB only');
  return dc(['exec', '-T', 'postgres', 'psql', '-v', 'ON_ERROR_STOP=1', '-U', 'bench', '-d', database, '-At'], { input: query }).trim();
}
const write = (name, value) => fs.writeFileSync(path.join(generated, name), value);
function init() {
  fs.mkdirSync(generated, { recursive: true });
  fs.mkdirSync(path.join(generated, 'jars'), { recursive: true });
  fs.mkdirSync(path.join(generated, 'gateway'), { recursive: true });
  if (!fs.existsSync(path.join(generated, 'private.pem'))) {
    const keys = generateKeyPairSync('rsa', { modulusLength: 2048 });
    write('private.pem', keys.privateKey.export({ type: 'pkcs8', format: 'pem' }));
    write('jwks.json', JSON.stringify({ keys: [{ ...keys.publicKey.export({ format: 'jwk' }), kid: 'isolated-bench', use: 'sig', alg: 'RS256' }] }));
  }
  write('init.sql', ['product','user','inventory','promotion','payment','order'].map(s => `CREATE DATABASE ${s}_service;`).join('\n'));
  fs.copyFileSync(path.join(repo, 'go/services/api-gateway/config/dynamic.yml'), path.join(generated, 'gateway/middleware.yml'));
  const services = {};
  const base = (image, extra = {}) => ({ image, restart: 'no', networks: ['bench'], ...extra });
  const java = (name, port = 8080, extra = {}) => base('eclipse-temurin:21-jre-alpine', {
    command: ['java','-Xms128m','-Xmx384m','-jar',`/jars/${name}.jar`],
    cpus: name === 'order' || name === 'inventory' ? 2 : 1, mem_limit: '768m',
    volumes: [`${generated.replaceAll('\\','/')}/jars:/jars:ro`],
    environment: { SPRING_PROFILES_ACTIVE: 'local', SERVER_PORT: String(port), DB_URL: `jdbc:postgresql://postgres:5432/${name}_service`, DB_USERNAME: 'bench', DB_PASSWORD: 'bench', KAFKA_BOOTSTRAP_SERVERS: 'kafka:9092', KAFKA_BROKERS: 'kafka:9092', REDIS_HOST: 'redis', REDIS_PASSWORD: '', TRACING_SAMPLING_PROBABILITY: '0', MANAGEMENT_TRACING_ENABLED: 'false', ...extra },
  });
  services.postgres = base('postgres:16-alpine', { cpus: 3, mem_limit: '2g', environment: { POSTGRES_USER:'bench',POSTGRES_PASSWORD:'bench' }, command:['postgres','-c','wal_level=logical','-c','max_replication_slots=12','-c','max_wal_senders=12','-c','max_connections=300','-c','shared_preload_libraries=pg_stat_statements'], ports:['127.0.0.1:35432:5432'], volumes:[`data:/var/lib/postgresql/data`,`${generated.replaceAll('\\','/')}/init.sql:/docker-entrypoint-initdb.d/01.sql:ro`], healthcheck:{test:['CMD-SHELL','pg_isready -U bench'],interval:'2s',timeout:'2s',retries:60} });
  services.redis = base('redis:7.2-alpine', { command:['redis-server','--appendonly','yes','--maxmemory-policy','noeviction'], cpus:1, mem_limit:'512m',volumes:['redis-data:/data'] });
  services['checkout-redis'] = base('redis:7.2-alpine', { command:['redis-server','--appendonly','yes','--maxmemory-policy','noeviction'], cpus:1, mem_limit:'512m',volumes:['checkout-data:/data'] });
  services.kafka = base('confluentinc/cp-kafka:7.6.0', { cpus:2,mem_limit:'1g',environment:{CLUSTER_ID:'MkU3OEVBNTcwNTJENDM2Qk',KAFKA_NODE_ID:'1',KAFKA_PROCESS_ROLES:'broker,controller',KAFKA_CONTROLLER_QUORUM_VOTERS:'1@kafka:9093',KAFKA_LISTENERS:'PLAINTEXT://:9092,CONTROLLER://:9093',KAFKA_ADVERTISED_LISTENERS:'PLAINTEXT://kafka:9092',KAFKA_LISTENER_SECURITY_PROTOCOL_MAP:'CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT',KAFKA_CONTROLLER_LISTENER_NAMES:'CONTROLLER',KAFKA_INTER_BROKER_LISTENER_NAME:'PLAINTEXT',KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR:'1',KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR:'1',KAFKA_TRANSACTION_STATE_LOG_MIN_ISR:'1',KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS:'0',KAFKA_NUM_PARTITIONS:'6',KAFKA_HEAP_OPTS:'-Xms256m -Xmx512m'},volumes:['kafka-data:/var/lib/kafka/data'] });
  services.temporal = base('temporalio/auto-setup:1.25.2',{cpus:1,mem_limit:'768m',environment:{DB:'postgres12',DB_PORT:'5432',POSTGRES_USER:'bench',POSTGRES_PWD:'bench',POSTGRES_SEEDS:'postgres'},depends_on:{postgres:{condition:'service_healthy'}}});
  services.connect = base('debezium/connect:3.0.0.Final',{cpus:1,mem_limit:'1g',ports:['127.0.0.1:38084:8083'],environment:{BOOTSTRAP_SERVERS:'kafka:9092',GROUP_ID:'checkout-bench-connect',CONFIG_STORAGE_TOPIC:'bench.connect.configs',OFFSET_STORAGE_TOPIC:'bench.connect.offsets',STATUS_STORAGE_TOPIC:'bench.connect.status',CONFIG_STORAGE_REPLICATION_FACTOR:'1',OFFSET_STORAGE_REPLICATION_FACTOR:'1',STATUS_STORAGE_REPLICATION_FACTOR:'1',HEAP_OPTS:'-Xms256m -Xmx512m'},depends_on:['kafka']});
  services.provider = base('node:24-alpine',{command:['node','/bench/provider.mjs'],cpus:1,mem_limit:'256m',ports:['127.0.0.1:38091:8080'],volumes:[`${dir.replaceAll('\\','/')}:/bench:ro`],environment:{BENCH_WEBHOOK_URL:'http://gateway:80/v1/payments/webhook/stripe'}});
  services['product-service'] = java('product',8081,{EXPORT_CURSOR_SECRET:'bench-only-export-cursor-secret-32-bytes',PRODUCT_WARMUP_TIMEOUT:'90s'});
  services['inventory-service'] = java('inventory');
  services['promotion-service'] = java('promotion',8085,{PRODUCT_BASE_URL:'http://product-service:8081'});
  services['payment-service'] = java('payment',8080,{PAYMENT_DATABASE_URL:'jdbc:postgresql://postgres:5432/payment_service',PAYMENT_DATABASE_USERNAME:'bench',PAYMENT_KAFKA_BROKERS:'kafka:9092',PAYMENT_STRIPE_SECRET_KEY:'sk_test_isolated_benchmark',PAYMENT_STRIPE_PUBLISHABLE_KEY:'pk_test_isolated_benchmark',PAYMENT_STRIPE_WEBHOOK_SECRETS:'whsec_isolated_benchmark',PAYMENT_CRYPTO_LOCAL_KEY:'MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=',PAYMENT_CRYPTO_LOCAL_KEY_ID:'bench',BENCH_STRIPE_BASE:'http://provider:8080'});
  services['payment-service'].command = ['java','-Xms128m','-Xmx384m','-cp','/payment/BOOT-INF/classes:/payment/BOOT-INF/lib/*:/hook','bench.BenchPayment'];
  services['payment-service'].environment.SPRING_PROFILES_ACTIVE='dev';
  services['payment-service'].volumes.push(`${generated.replaceAll('\\','/')}/payment:/payment:ro`,`${generated.replaceAll('\\','/')}/hook:/hook:ro`);
  services['order-service'] = java('order',8080,{TEMPORAL_TARGET:'temporal:7233',INVENTORY_BASE_URL:'http://inventory-service:8080',PROMOTION_BASE_URL:'http://promotion-service:8085',PAYMENT_BASE_URL:'http://payment-service:8080'});
  services['cart-service'] = base('e-commerce-microservices-cart-service:latest',{cpus:1,mem_limit:'256m',ports:['127.0.0.1:38085:8080'],volumes:[`${repo.replaceAll('\\','/')}/go/services/cart-service/configs:/app/configs:ro`],environment:{CART_REDIS_ADDRS:'redis:6379',CART_REDIS_PASSWORD:'',CART_KAFKA_BROKERS:'kafka:9092',CART_PRODUCT_ADDRESS:'product-service:9091',CART_LOGGING_LEVEL:'info'}});
  services['user-service'] = base('e-commerce-microservices-user-service:latest',{cpus:1,mem_limit:'256m',volumes:[`${repo.replaceAll('\\','/')}/go/services/user-service/configs:/app/configs:ro`],environment:{USER_DATABASE_HOST:'postgres',USER_DATABASE_NAME:'user_service',USER_DATABASE_USERNAME:'bench',DB_PASSWORD:'bench',USER_REDIS_HOST:'redis',REDIS_PASSWORD:'',USER_KAFKA_BROKERS:'kafka:9092',USER_KAFKA_CONSUMER_ENABLED:'false'}});
  services['gateway-auth'] = base('e-commerce-microservices-gateway-auth:latest',{cpus:1,mem_limit:'256m',environment:{AUTH_JWT_ISSUER:'https://auth.ecommerce.local',AUTH_JWT_AUDIENCE:'ecommerce-api',AUTH_JWT_ALLOWED_ALGORITHMS:'RS256',AUTH_JWKS_URL:'http://provider:8080/.well-known/jwks.json',AUTH_REVOCATION_REDIS_URL:'redis://redis:6379/0'}});
  services['checkout-service'] = java('checkout',8080,{CHECKOUT_REDIS_URL:'redis://checkout-redis:6379/0',CHECKOUT_TOKEN_HMAC_KEYS:'v1:bG9jYWwtZGV2LW9ubHktY2hlY2tvdXQtaG1hYy1rZXktMDAwMSE=',CHECKOUT_TOKEN_HMAC_ACTIVE_VERSION:'v1',CHECKOUT_ENCRYPTION_KEYS:'v1:bG9jYWwtZGV2LW9ubHktY2hlY2tvdXQtYWVzLWtleSE=',CHECKOUT_ENCRYPTION_ACTIVE_VERSION:'v1',CHECKOUT_CART_BASE_URL:'http://cart-service:8080',CHECKOUT_USER_BASE_URL:'http://user-service:8083',CHECKOUT_PRODUCT_GRPC_TARGET:'product-service:9091',CHECKOUT_INVENTORY_BASE_URL:'http://inventory-service:8080',CHECKOUT_PROMOTION_BASE_URL:'http://promotion-service:8085',CHECKOUT_ORDER_BASE_URL:'http://order-service:8080',CHECKOUT_VOUCHER_OPTIONS_ENABLED:'true',CHECKOUT_MEDIA_CDN_BASE_URL:'http://gateway/media',CHECKOUT_MEDIA_PLACEHOLDER_URL:'http://gateway/media/placeholder.svg',CHECKOUT_MEDIA_REQUIRE_HTTPS:'false'});
  services.gateway = base('traefik:v3.7.1',{cpus:1,mem_limit:'256m',ports:['127.0.0.1:38088:80'],command:['--entrypoints.web.address=:80','--providers.file.directory=/config','--log.level=WARN'],volumes:[`${generated.replaceAll('\\','/')}/gateway:/config:ro`]});
  services.gateway.mem_limit=process.env.BENCH_EDGE_MEMORY??'256m';
  services['gateway-auth'].mem_limit=process.env.BENCH_AUTH_MEMORY??'256m';
  const routers = {}, upstreams = {};
  function route(name, rule, service, port, middleware = ['authenticated@file'], priority=10) {
    routers[name]={rule,entryPoints:['web'],service,middlewares:middleware,priority};
    upstreams[service]={loadBalancer:{servers:[{url:`http://${service}:${port}`}],serversTransport:'business-api@file'}};
  }
  route('preview','Path(`/api/v1/checkout/preview`)','checkout-service',8080,['authenticated@file','rate-limit-checkout-preview@file'],50);
  route('submit','Path(`/api/v1/checkout/submit`)','checkout-service',8080,['authenticated@file','rate-limit-checkout-submit@file'],50);
  route('checkout','PathPrefix(`/api/v1/checkout/`)','checkout-service',8080,['authenticated@file','rate-limit-api@file']);
  route('orders','PathPrefix(`/api/v1/orders`)','order-service',8080,['authenticated@file','rate-limit-api@file']);
  route('cart','PathPrefix(`/api/v1/carts/`)','cart-service',8080,['authenticated@file','rate-limit-api@file']);
  route('promotion','PathPrefix(`/api/v1/me/promotion-`) || PathPrefix(`/api/v1/admin/promotion`)','promotion-service',8085);
  route('webhook','Path(`/v1/payments/webhook/stripe`)','payment-service',8080,[]);
  write('gateway/routes.yml',JSON.stringify({http:{routers,services:upstreams}},null,2));
  for(const name of ['cart-service','user-service','gateway-auth'])services[name].image=`checkout-bench/${name}:current`;
  for(const name of ['gateway','provider','connect','cart-service','postgres'])services[name].networks=['bench','edge'];
  write('compose.json',JSON.stringify({name:project,services,networks:{bench:{internal:true},edge:{}},volumes:{data:{},'redis-data':{},'checkout-data':{},'kafka-data':{}}},null,2));
  console.log('Prepared isolated network, private test issuer and local-only ports; no .env imported.');
}
function build() {
  const sourceHashes={};
  const visit=(folder)=>{for(const entry of fs.readdirSync(folder,{withFileTypes:true})){const target=path.join(folder,entry.name);if(entry.isDirectory())visit(target);else sourceHashes[path.relative(repo,target).replaceAll('\\','/')]=createHash('sha256').update(fs.readFileSync(target)).digest('hex');}};
  for(const name of ['product','promotion','inventory','order','checkout','payment']){visit(path.join(repo,`java/services/${name}-service/src/main`));const p=path.join(repo,`java/services/${name}-service/pom.xml`);sourceHashes[path.relative(repo,p).replaceAll('\\','/')]=createHash('sha256').update(fs.readFileSync(p)).digest('hex');}
  for(const name of ['cart-service','user-service','gateway-auth'])visit(path.join(repo,`go/services/${name}/internal`));
  write('source-hashes.json',JSON.stringify(sourceHashes,null,2));
  for (const name of ['product','promotion','inventory','order','checkout','payment']) {
    console.log(`Building current ${name} source`);
    const log=path.join(generated,`build-${name}.log`);
    const fd=fs.openSync(log,'w');
    try { docker(['run','--rm','-v',`${repo}:/repo`,'-v',`${name}-mvn-repo:/root/.m2`,'-w',`/repo/java/services/${name}-service`,'maven:3.9-eclipse-temurin-21','mvn','-B','-q','-Dmaven.test.skip=true','-Dos.detected.classifier=linux-x86_64','package'],{stdio:['ignore',fd,fd]}); }
    finally { fs.closeSync(fd); }
    fs.copyFileSync(path.join(repo,`java/services/${name}-service/target/${name}-service-1.0.0-SNAPSHOT.jar`),path.join(generated,`jars/${name}.jar`));
  }
  fs.mkdirSync(path.join(generated,'payment'),{recursive:true});
  fs.mkdirSync(path.join(generated,'hook'),{recursive:true});
  docker(['run','--rm','-v',`${dir}:/bench`,'-w','/bench/.generated/payment','maven:3.9-eclipse-temurin-21','jar','xf','/bench/.generated/jars/payment.jar'],{stdio:'inherit'});
  docker(['run','--rm','-v',`${dir}:/bench`,'maven:3.9-eclipse-temurin-21','javac','-cp','/bench/.generated/payment/BOOT-INF/classes:/bench/.generated/payment/BOOT-INF/lib/*','-d','/bench/.generated/hook','/bench/java/BenchPayment.java'],{stdio:'inherit'});
  const hashes={};
  for(const name of fs.readdirSync(path.join(generated,'jars'))) hashes[name]=createHash('sha256').update(fs.readFileSync(path.join(generated,'jars',name))).digest('hex');
  write('artifact-hashes.json',JSON.stringify(hashes,null,2));
  for(const name of ['cart-service','user-service','gateway-auth']){
    console.log(`Building current ${name} source`);
    const fd=fs.openSync(path.join(generated,`build-${name}.log`),'w');
    try{docker(['build','-t',`checkout-bench/${name}:current`,'-f',path.join(repo,`go/services/${name}/Dockerfile`),path.join(repo,'go')],{stdio:['ignore',fd,fd]});}finally{fs.closeSync(fd);}
  }
}
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const command=process.argv[2]??'init';
  if(command==='init') init();
  else if(command==='build') build();
  else if(command==='infra') dc(['up','-d','postgres','redis','checkout-redis','kafka','temporal','provider','gateway','gateway-auth','connect'],{stdio:'inherit'});
  else if(command==='stop') dc(['stop'],{stdio:'inherit'});
  else throw Error('commands: init, build, infra, stop');
}
