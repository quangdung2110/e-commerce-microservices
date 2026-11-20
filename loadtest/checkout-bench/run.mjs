import fs from 'node:fs';
import path from 'node:path';
import { createSign, randomUUID, createHash } from 'node:crypto';
import { performance, monitorEventLoopDelay } from 'node:perf_hooks';
import { spawn, execFileSync } from 'node:child_process';
import { dir, generated, dc, docker, repo } from './prepare.mjs';
import { summarize,evaluateSlo } from './stats.mjs';
import { recoverSubmission } from './recovery.mjs';
import { recoverPreview } from './preview.mjs';
import { submitConcurrency,submitChecks } from './submit-stats.mjs';
const args=Object.fromEntries(process.argv.slice(2).map(x=>{const i=x.indexOf('=');return [x.slice(0,i),x.slice(i+1)];}));
const inContainer=process.env.BENCH_CONTAINER_GENERATOR==='1';
const apiBase=process.env.BENCH_BASE_URL??'http://127.0.0.1:38088';
const providerBase=process.env.BENCH_PROVIDER_URL??'http://127.0.0.1:38091';
const count=Number(args.count??5),rate=Number(args.rate??0),profile=args.profile??'machine',timeoutMs=Number(args.timeout??180000);
const concurrencyLimit=Number(args.concurrency??0),durationMs=Number(args.duration??0);
if(!Number.isInteger(count)||count<1||count>100000||rate<0||!['machine','journey','submit'].includes(profile)||!Number.isInteger(concurrencyLimit)||concurrencyLimit<0||concurrencyLimit>count||durationMs<0||(durationMs&&!concurrencyLimit))throw Error('invalid workload');
const offsetFile=path.join(generated,'cursor.json');
const offset=Number(args.offset??(fs.existsSync(offsetFile)?JSON.parse(fs.readFileSync(offsetFile)).next:0));
const fixtures=JSON.parse(fs.readFileSync(path.join(generated,'fixtures.json'))).slice(offset,offset+count);
if(args.promotion!=='half')for(const f of fixtures)delete f.promotionId;
if(fixtures.length!==count)throw Error('insufficient fresh fixtures; seed more distinct users');
const runId=args.name??`${new Date().toISOString().replaceAll(':','-')}-${profile}-${count}-${rate}`;
if(!/^[A-Za-z0-9_.-]+$/.test(runId))throw Error('invalid run name');
const out=path.join(dir,'results',runId);if(fs.existsSync(out))throw Error('run already exists; use a fresh name');fs.mkdirSync(out,{recursive:true});
fs.writeFileSync(offsetFile,JSON.stringify({next:offset+count}));
const privateKey=fs.readFileSync(path.join(generated,'private.pem'));
function jwt(f){const now=Math.floor(Date.now()/1000);const enc=x=>Buffer.from(JSON.stringify(x)).toString('base64url');const raw=`${enc({alg:'RS256',kid:'isolated-bench',typ:'JWT'})}.${enc({iss:'https://auth.ecommerce.local',aud:'ecommerce-api',sub:f.userId,jti:randomUUID(),sid:randomUUID(),roles:['BUYER'],iat:now,nbf:now-1,exp:now+900})}`;return `${raw}.${createSign('RSA-SHA256').update(raw).sign(privateKey,'base64url')}`;}
const tokens=fixtures.map(jwt); // signing/fixture setup excluded from offered-arrival clock
const rows=[],requests=[],buffer=[],sessionBuffer=[],setupRequests=[],setupBuffer=[],prepared=new Array(count);
let stopInjection=false;
const flush=()=>{if(buffer.length)fs.appendFileSync(path.join(out,'requests.jsonl'),buffer.splice(0).join(''));if(sessionBuffer.length)fs.appendFileSync(path.join(out,'sessions.jsonl'),sessionBuffer.splice(0).join(''));if(setupBuffer.length)fs.appendFileSync(path.join(out,'setup-requests.jsonl'),setupBuffer.splice(0).join(''));};
const flushTimer=setInterval(flush,1000);
flushTimer.unref();
const loop=monitorEventLoopDelay({resolution:20});loop.enable();
const config={runId,count,rate,profile,concurrencyLimit,durationMs,timedScope:profile==='submit'?'Submit -> client-visible Order PAID; Preview excluded':'Preview -> client-visible Order PAID',promotion:args.promotion??'none',timeoutMs,offset,startedAt:new Date().toISOString(),nodeVersion:process.version,provider:'isolated HTTP simulator through real Stripe SDK',generator:inContainer?'Linux container on direct Docker network; shared physical host':'host Node native fetch; shared host diagnostic',admissionImplemented:false,productionGatewayMiddleware:true,fixtureHash:createHash('sha256').update(JSON.stringify(fixtures)).digest('hex')};
fs.writeFileSync(path.join(out,'config.json'),JSON.stringify(config,null,2));
fs.copyFileSync(path.join(generated,'artifact-hashes.json'),path.join(out,'artifact-hashes.json'));
fs.writeFileSync(path.join(out,'harness-hashes.json'),JSON.stringify(Object.fromEntries(fs.readdirSync(dir).filter(n=>n.endsWith('.mjs')).map(n=>[n,createHash('sha256').update(fs.readFileSync(path.join(dir,n))).digest('hex')])),null,2));
fs.mkdirSync(path.join(out,'harness'));
for(const name of fs.readdirSync(dir).filter(n=>n.endsWith('.mjs')))fs.copyFileSync(path.join(dir,name),path.join(out,'harness',name));
if(fs.existsSync(path.join(generated,'source-hashes.json')))fs.copyFileSync(path.join(generated,'source-hashes.json'),path.join(out,'source-hashes.json'));
const monitor=inContainer?null:spawn(process.execPath,[path.join(dir,'monitor.mjs'),out],{stdio:['ignore','ignore','ignore']});
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
fs.writeFileSync(path.join(out,'provider-before.json'),JSON.stringify(await(await fetch(`${providerBase}/bench/stats`)).json(),null,2));
async function http(row,stage,method,route,body,extra={}){
  const start=performance.now();let status=0,data=null,retryAfter=2,bodyError=null;
  try{const r=await fetch(`${extra.provider?providerBase:apiBase}${route}`,{method,headers:{'content-type':extra.provider?'application/x-www-form-urlencoded':'application/json',...(extra.provider?{}:{Authorization:`Bearer ${tokens[row.actor]}`,'X-Request-Id':`${runId.slice(0,45)}-${row.actor}-${requests.length+setupRequests.length}`}),...extra.headers},body:body===undefined?undefined:extra.provider?new URLSearchParams(body):JSON.stringify(body),signal:AbortSignal.timeout(Math.max(1,Math.min(25000,row.deadline-Date.now())))});status=r.status;retryAfter=Number(r.headers.get('retry-after')??2);data=await r.json().catch(()=>{bodyError='INVALID_JSON';return null;});}catch(error){row.lastNetworkError=error.cause?.code??error.name;}
  const sample={actor:row.actor,stage,status,code:data?.code??null,error:status===0?row.lastNetworkError:bodyError,durationMs:performance.now()-start,at:Date.now()};
  if(stage.startsWith('setup_')){setupRequests.push(sample);setupBuffer.push(JSON.stringify(sample)+'\n');}else{requests.push(sample);buffer.push(JSON.stringify(sample)+'\n');if(args.failFast==='true'&&(status===0||status>=400||sample.error))stopInjection=true;}
  if(buffer.length>=1000||setupBuffer.length>=1000)flush();return{status,data,retryAfter};
}
async function actor(f,i,scheduledAt){const row={actor:i,index:f.index,userId:f.userId,lineCount:f.lineCount,hot:f.hot,scheduledAt,startedAt:Date.now(),thinkMs:0};row.deadline=row.startedAt+timeoutMs;
  const think=async(ms)=>{const at=Date.now();row.plannedThinkMs=(row.plannedThinkMs??0)+ms;await sleep(ms);row.thinkMs+=Date.now()-at;};
  try{
    if(profile==='submit'){
      row.previewPreparedAt=prepared[i].at;row.checkoutToken=prepared[i].token;row.previewAt=row.startedAt;
    }else{
      const key=randomUUID();
      const p=await recoverPreview({request:()=>http(row,'preview','POST','/api/v1/checkout/preview',{delivery_address_id:f.addressId,...(f.promotionId?{promotion_id:f.promotionId}:{})},{headers:{'Idempotency-Key':key}}),pause:retry=>sleep(Math.max(1,retry??2)*1000+i%251),expired:()=>Date.now()>=row.deadline});
      row.previewAt=Date.now();row.checkoutToken=p.data.checkout_token;
    }
    if(profile==='journey')await think(5000+(f.index*7919)%10001);
    row.submitAt=Date.now();if(profile==='submit'){row.startedAt=row.submitAt;row.deadline=row.submitAt+timeoutMs;}
    let ready=await http(row,'submit','POST','/api/v1/checkout/submit',{checkout_token:row.checkoutToken});
    ready=await recoverSubmission(ready,{expired:()=>Date.now()>=row.deadline,pause:retry=>sleep(Math.max(1,retry??2)*1000+i%251),read:()=>http(row,'submission_status','GET',`/api/v1/checkout/submissions/${row.checkoutToken}`),replay:()=>http(row,'submit_replay','POST','/api/v1/checkout/submit',{checkout_token:row.checkoutToken})});
    if(!ready.data?.order_id)throw Error('ready_timeout');
    row.readyAt=Date.now();row.orderId=String(ready.data.order_id);
    const secret=ready.data.payment?.client_secret;const pi=secret?.split('_secret_')[0];if(!pi?.startsWith('pi_'))throw Error('missing_payment_client_data');
    if(profile==='journey')await think(20000+(f.index*3571)%20001);
    const confirmed=await http(row,'provider_confirm','POST',`/v1/payment_intents/${pi}/confirm`,{payment_method:'pm_card_visa'},{provider:true});
    if(confirmed.status===200&&confirmed.data.status==='succeeded')row.captureAt=confirmed.data.bench_captured_at_ms;
    else if(confirmed.status===0||confirmed.status>=500){
      row.confirmationUnknown=true;
      if(row.deadline-Date.now()>1000){const fact=await http(row,'provider_retrieve','GET',`/v1/payment_intents/${pi}`,undefined,{provider:true});if(fact.data?.status==='succeeded')row.captureAt=fact.data.bench_captured_at_ms;}
    }else throw Error(`confirm_${confirmed.status}`);
    while(Date.now()<row.deadline){const order=await http(row,'order_status','GET',`/api/v1/orders/${row.orderId}`);if(order.data?.payment_status==='PAID'){row.paidAt??=Date.now();if(order.data.order_status==='PROCESSING'){row.convergedAt=Date.now();break;}if(profile==='submit')break;}else if(order.data?.order_status==='CANCELLED')throw Error('order_cancelled');if(row.paidAt&&Date.now()-row.paidAt>15000)break;await sleep(2000+i%1001);}
    if(!row.paidAt)throw Error('paid_timeout');row.outcome='paid';
  }catch(error){row.outcome=error.message;}
  finally{row.finishedAt=Date.now();if(args.failFast==='true'&&row.outcome!=='paid')stopInjection=true;delete row.deadline;delete row.checkoutToken;rows.push(row);sessionBuffer.push(JSON.stringify(row)+'\n');if(sessionBuffer.length>=1000)flush();}
}
// Record environment without secrets; baseline never mutates application limits.
if(!inContainer){
fs.writeFileSync(path.join(out,'docker-version.json'),docker(['version','--format','{{json .Server}}']));
fs.writeFileSync(path.join(out,'git-status.txt'),execFileSync('git',['status','--porcelain'],{cwd:repo,encoding:'utf8'}));
fs.writeFileSync(path.join(out,'git-head.txt'),execFileSync('git',['rev-parse','HEAD'],{cwd:repo,encoding:'utf8'}));
}
if(profile==='submit'){
  const before=Date.now();let next=0;
  await Promise.all(Array.from({length:2},async()=>{while(next<count){const i=next++,f=fixtures[i],key=randomUUID(),row={actor:i,deadline:Date.now()+180000};
    const p=await recoverPreview({request:()=>http(row,'setup_preview','POST','/api/v1/checkout/preview',{delivery_address_id:f.addressId,...(f.promotionId?{promotion_id:f.promotionId}:{})},{headers:{'Idempotency-Key':key}}),pause:retry=>sleep(Math.max(1,retry??2)*1000),expired:()=>Date.now()>=row.deadline});
    prepared[i]={token:p.data.checkout_token,at:Date.now()};
  }}));
  fs.writeFileSync(path.join(out,'setup-summary.json'),JSON.stringify({at:new Date().toISOString(),prepared:count,elapsedMs:Date.now()-before,requests:setupRequests.length,recoveredErrors:setupRequests.filter(r=>r.status===0||r.status>=400||r.error).length},null,2));
  if(args.barrier==='host'){
    const waitUntil=Date.now()+60000;while(!fs.existsSync(path.join(out,'release-measurement'))){if(Date.now()>waitUntil)throw Error('measurement barrier timeout');await sleep(50);}
  }
  flush();loop.reset();
}
const start=Date.now();fs.writeFileSync(path.join(out,'measurement-start.json'),JSON.stringify({at:new Date(start).toISOString(),epochMs:start}));
if(concurrencyLimit){
  let next=0;
  await Promise.all(Array.from({length:concurrencyLimit},async()=>{while(next<count&&!stopInjection&&(!durationMs||Date.now()<start+durationMs)){const i=next++;await actor(fixtures[i],i,Date.now());}}));
}else{
  const tasks=[];for(let i=0;i<count;i++){const scheduledAt=start+(rate?i*1000/rate:0);if(scheduledAt>Date.now())await sleep(scheduledAt-Date.now());tasks.push(actor(fixtures[i],i,scheduledAt));}await Promise.all(tasks);
}
const measurementEnd=Date.now();clearInterval(flushTimer);flush();loop.disable();
fs.writeFileSync(path.join(out,'stop-monitor'),'');await sleep(6000);monitor?.kill();
const summary={...config,...summarize(rows,requests),generator:{eventLoopP99Ms:loop.percentile(99)/1e6,eventLoopMaxMs:loop.max/1e6,memory:process.memoryUsage()},finishedAt:new Date().toISOString()};
summary.funnel={started:rows.length,quoted:rows.filter(r=>r.previewAt).length,paymentReady:rows.filter(r=>r.readyAt).length,clientKnowsCapture:rows.filter(r=>r.captureAt).length,clientPaid:rows.filter(r=>r.paidAt).length};
if(profile==='submit'){
  delete summary.previewMs;
  summary.measurementStartedAt=new Date(start).toISOString();summary.measurementFinishedAt=new Date(measurementEnd).toISOString();
  summary.strict=submitChecks(rows,requests);
  summary.concurrency=submitConcurrency(rows,start,durationMs?Math.min(measurementEnd,start+durationMs):measurementEnd,concurrencyLimit||count);
  summary.fixtureExhausted=Boolean(durationMs&&rows.length===count&&measurementEnd<start+durationMs);
  summary.stoppedOnError=stopInjection;
}else summary.slo=evaluateSlo(summary);
if(rate){const span=count/rate;summary.arrivals={targetPerSecond:rate,actualPerSecond:count/((Math.max(...rows.map(r=>r.startedAt))-start)/1000+1/rate),paidDuringInjection:rows.filter(r=>r.paidAt>=start&&r.paidAt<start+span*1000).length,injectionSeconds:span};}
fs.writeFileSync(path.join(out,'provider-after.json'),JSON.stringify(await(await fetch(`${providerBase}/bench/stats`)).json(),null,2));
fs.writeFileSync(path.join(out,'summary.json'),JSON.stringify(summary,null,2));
console.log(JSON.stringify(summary,null,2));
