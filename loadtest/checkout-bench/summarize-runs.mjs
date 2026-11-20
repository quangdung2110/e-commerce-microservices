import fs from 'node:fs';
import path from 'node:path';
import {dir}from'./prepare.mjs';
const folder=path.join(dir,'results');
for(const name of fs.readdirSync(folder)){
 const root=path.join(folder,name),file=path.join(root,'summary.json');if(!fs.existsSync(file))continue;
 const s=JSON.parse(fs.readFileSync(file));
 const sessions=fs.readFileSync(path.join(root,'sessions.jsonl'),'utf8').trim().split('\n').filter(Boolean).map(JSON.parse);
 const funnel={started:sessions.length,quoted:sessions.filter(r=>r.previewAt).length,paymentReady:sessions.filter(r=>r.readyAt).length,clientKnowsCapture:sessions.filter(r=>r.captureAt).length,clientPaid:sessions.filter(r=>r.paidAt).length};
 fs.writeFileSync(path.join(root,'funnel.json'),JSON.stringify(funnel,null,2));
 const extra={};
 for(const line of(fs.existsSync(path.join(root,'docker-stats.jsonl'))?fs.readFileSync(path.join(root,'docker-stats.jsonl'),'utf8').trim().split('\n'):[]).filter(Boolean))for(const sample of JSON.parse(line).samples){const service=sample.Name.replace('checkout-payment-bench-','').replace(/-1$/,'');const old=extra[service]??{cpuMax:0,hikariPendingMax:0};old.cpuMax=Math.max(old.cpuMax,parseFloat(sample.CPUPerc));extra[service]=old;}
 for(const line of(fs.existsSync(path.join(root,'metrics.jsonl'))?fs.readFileSync(path.join(root,'metrics.jsonl'),'utf8').trim().split('\n'):[]).filter(Boolean))for(const value of JSON.parse(line).values??[]){const e=extra[value.service]??{cpuMax:0,hikariPendingMax:0};for(const metric of(value.metrics??'').split('\n'))if(metric.startsWith('hikaricp_connections_pending'))e.hikariPendingMax=Math.max(e.hikariPendingMax,Number(metric.split(' ').at(-1)));extra[value.service]=e;}
 fs.writeFileSync(path.join(root,'resources-summary.json'),JSON.stringify(extra,null,2));
 console.log(JSON.stringify({name,sessions:s.sessions,paid:s.paid,success:s.successRate,peak:s.concurrency.peak,average:s.concurrency.timeWeightedMean,readyP95:s.readyMs.p95,readyP99:s.readyMs.p99,machineP99:s.machineMs.p99,previewP99:s.previewMs?.p99??null,captureP99:s.captureVisibleMs.p99,http429:Object.entries(s.requests).filter(([k])=>k.endsWith(':429')).reduce((a,[,n])=>a+n,0),outcomes:s.outcomes,...(process.argv.includes('--compact')?{}:{resources:extra}),excluded:fs.existsSync(path.join(root,'excluded-pilot.json'))}));
}
