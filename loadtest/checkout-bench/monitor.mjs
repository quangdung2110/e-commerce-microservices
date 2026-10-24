import fs from 'node:fs';
import path from 'node:path';
import { dc, docker, project, sql } from './prepare.mjs';
const out=process.argv[2],sleep=ms=>new Promise(r=>setTimeout(r,ms));
const names=dc(['ps','--format','json']).trim().split('\n').filter(Boolean).map(x=>JSON.parse(x).Name);
if(fs.existsSync(path.join(out,'generator-container.json')))names.push(JSON.parse(fs.readFileSync(path.join(out,'generator-container.json'))).name);
fs.writeFileSync(path.join(out,'container-provenance.json'),JSON.stringify(JSON.parse(docker(['inspect',...names])).map(x=>({name:x.Name,imageId:x.Image,image:x.Config.Image,limits:{memory:x.HostConfig.Memory,nanoCpus:x.HostConfig.NanoCpus},started:x.State.StartedAt})),null,2));
while(!fs.existsSync(path.join(out,'stop-monitor'))){const at=new Date().toISOString();
  try{fs.appendFileSync(path.join(out,'docker-stats.jsonl'),JSON.stringify({at,samples:docker(['stats','--no-stream','--format','{{json .}}',...names]).trim().split('\n').filter(Boolean).map(JSON.parse)})+'\n');}catch{}
  try{const values=JSON.parse(dc(['exec','-T','provider','node','-e',`Promise.all([['checkout-service',8080],['order-service',8080],['inventory-service',8080],['payment-service',8080],['promotion-service',8085],['product-service',8081]].map(async([s,p])=>{try{const r=await fetch('http://'+s+':'+p+'/actuator/prometheus',{signal:AbortSignal.timeout(2000)});const t=await r.text();return {service:s,status:r.status,metrics:t.split('\\n').filter(l=>/^(hikaricp_|order_|checkout_|inventory_commands_|http_server_requests_seconds_(count|sum)|jvm_memory_used_bytes|process_cpu_usage|system_cpu_usage)/.test(l)).join('\\n')}}catch(e){return{service:s,error:e.name}}})).then(x=>console.log(JSON.stringify(x)))`]));fs.appendFileSync(path.join(out,'metrics.jsonl'),JSON.stringify({at,values})+'\n');}catch{}
  try{const state=sql('order_service',`SELECT json_build_object('orders',(SELECT json_object_agg(status,n) FROM (SELECT status,count(*) n FROM orders GROUP BY status)s),'submissions',(SELECT json_object_agg(state,n) FROM(SELECT state,count(*) n FROM order_checkout_submissions GROUP BY state)s),'db',(SELECT json_build_object('active',count(*) FILTER(WHERE state='active'),'lock_waiters',count(*) FILTER(WHERE wait_event_type='Lock')) FROM pg_stat_activity WHERE datname IN('order_service','inventory_service','payment_service')))::text;`);fs.appendFileSync(path.join(out,'database.jsonl'),JSON.stringify({at,state:JSON.parse(state)})+'\n');}catch(error){fs.appendFileSync(path.join(out,'monitor-errors.txt'),String(error.message).slice(0,200)+'\n');}
  try{const r=await fetch('http://127.0.0.1:38091/bench/stats');fs.appendFileSync(path.join(out,'provider.jsonl'),JSON.stringify({at,...await r.json()})+'\n');}catch{}
  await sleep(3000);
}
