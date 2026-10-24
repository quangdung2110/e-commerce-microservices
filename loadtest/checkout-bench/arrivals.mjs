import fs from 'node:fs';
import path from 'node:path';
import {spawn} from 'node:child_process';
import {createInterface} from 'node:readline';
import{dir,compose,project}from'./prepare.mjs';
import{distribution}from'./stats.mjs';
const name=process.argv[2];if(!/^[A-Za-z0-9_.-]+$/.test(name??''))throw Error('run name');
const out=path.join(dir,'results',name),config=JSON.parse(fs.readFileSync(path.join(out,'config.json')));
const rows=fs.readFileSync(path.join(out,'sessions.jsonl'),'utf8').trim().split('\n').filter(Boolean).map(JSON.parse);
const start=rows.reduce((v,r)=>Math.min(v,r.scheduledAt),Infinity),result={at:new Date().toISOString(),generatorScheduledStart:start,services:{}};
for(const service of ['gateway-auth','checkout-service']){
 const child=spawn('docker',['compose','-p',project,'-f',compose,'logs','--no-color','--since',config.startedAt,service],{stdio:['ignore','pipe','pipe']});
 let stderr='';child.stderr.on('data',chunk=>{stderr=(stderr+chunk).slice(-4000);});
 const done=new Promise((resolve,reject)=>{child.on('error',reject);child.on('close',code=>code===0?resolve():reject(Error(stderr||`logs exit ${code}`)));});
 const actors=new Map(),statuses={};
 for await(const line of createInterface({input:child.stdout,crlfDelay:Infinity})){
  const body=line.slice(line.indexOf('|')+1).trim();let e;try{e=JSON.parse(body);}catch{continue;}
  if((e.message??e.msg)!=='request completed')continue;
  const request=e.request_id??'';
  if(!request.startsWith(`${name}-`))continue;
  const actor=Number(request.slice(name.length+1).split('-')[0]);if(!Number.isInteger(actor))continue;
  const operation=e.operation??e.path??e.route;
  if(service==='checkout-service'&&operation!=='/api/v1/checkout/preview')continue;
  const stamp=Date.parse(e.time??e['@timestamp']??e.timestamp),duration=Number(e.duration_ms??0);
  if(!Number.isFinite(stamp))continue;
  const inferredStart=stamp-duration;
  actors.set(actor,Math.min(actors.get(actor)??Infinity,inferredStart));
  statuses[String(e.status)]=(statuses[String(e.status)]??0)+1;
 }
 await done;
 const lags=[...actors.values()].map(t=>t-start);
 result.services[service]={uniqueActorsObserved:actors.size,firstRequestWithin5s:lags.filter(ms=>ms<=5000).length,inferredStartLagMs:distribution(lags),statuses,note:'receive time inferred from completion timestamp minus logged duration; incomplete handlers may be absent'};
}
fs.writeFileSync(path.join(out,'arrival-evidence.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result,null,2));
