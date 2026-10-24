import fs from 'node:fs';
import path from 'node:path';
import { dc, sql, repo, generated } from './prepare.mjs';
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
async function waitFor(check,label,seconds=180){const end=Date.now()+seconds*1000;while(Date.now()<end){try{if(await check()){console.log(`${label} ready`);return;}}catch{}await sleep(2000);}throw Error(`${label} did not become ready`);}
const inside=async(url)=>JSON.parse(dc(['exec','-T','provider','node','-e',`fetch(${JSON.stringify(url)},{signal:AbortSignal.timeout(2500)}).then(async r=>console.log(JSON.stringify({status:r.status,body:await r.text()}))).catch(()=>console.log('{}'))` ]));
await waitFor(()=>{sql('user_service','SELECT 1');return true;},'PostgreSQL');
await waitFor(()=>{dc(['exec','-T','kafka','kafka-topics','--bootstrap-server','kafka:9092','--list'],{timeout:10000,stdio:['ignore','pipe','ignore']});return true;},'Kafka broker',180);
for(const database of ['order_service','inventory_service','payment_service'])sql(database,'CREATE EXTENSION IF NOT EXISTS pg_stat_statements;');
if(sql('user_service',"SELECT to_regclass('\"user\".user_profiles') IS NOT NULL")!=='t'){
  for(const name of ['0001_initial_user_schema.up.sql','0002_marketing_opt_in_default.up.sql'])sql('user_service',fs.readFileSync(path.join(repo,'go/services/user-service/migrations',name),'utf8'));
}
dc(['up','-d','product-service','inventory-service','promotion-service','payment-service','order-service','user-service','cart-service'],{stdio:'inherit'});
for(const [service,port,route]of [['product-service',8081,'/actuator/health/readiness'],['inventory-service',8080,'/actuator/health/readiness'],['promotion-service',8085,'/actuator/health/readiness'],['payment-service',8080,'/actuator/health/readiness'],['order-service',8080,'/actuator/health/readiness'],['user-service',8082,'/health/ready'],['cart-service',8080,'/health/ready']]){
  await waitFor(async()=>{const r=await inside(`http://${service}:${port}${route}`);return r.status===200;},service,240);
}
await waitFor(async()=>{const r=await fetch('http://127.0.0.1:38084/connectors');return r.ok;},'Kafka Connect');
for(const service of ['payment','order']){
  const template=JSON.parse(fs.readFileSync(path.join(repo,`java/services/${service}-service/src/main/resources/kafka/debezium-connector-${service}-outbox.json`)));
  const config=template.config;
  delete config['producer.override.sasl.jaas.config'];
  Object.assign(config,{'database.hostname':'postgres','database.port':'5432','database.user':'bench','database.password':'bench','database.dbname':`${service}_service`});
  const r=await fetch(`http://127.0.0.1:38084/connectors/${template.name}/config`,{method:'PUT',headers:{'content-type':'application/json'},body:JSON.stringify(config)});
  if(!r.ok)throw Error(`${service} connector registration ${r.status}: ${await r.text()}`);
  await waitFor(async()=>{const r=await fetch(`http://127.0.0.1:38084/connectors/${template.name}/status`);const s=await r.json();if(s.tasks?.some(t=>t.state==='FAILED'))throw Error('connector failed');return s.connector?.state==='RUNNING'&&s.tasks?.length&&s.tasks.every(t=>t.state==='RUNNING');},`${service} CDC`);
}
dc(['up','-d','checkout-service'],{stdio:'inherit'});
await waitFor(async()=>{const r=await inside('http://checkout-service:8080/health/ready');return r.status===200;},'Checkout',180);
fs.writeFileSync(path.join(generated,'startup.json'),JSON.stringify({at:new Date().toISOString(),services:dc(['ps','--format','json'])},null,2));
console.log('Isolated checkout stack ready. Fixture creation is next.');
