import fs from 'node:fs';
import path from 'node:path';
import {spawn} from 'node:child_process';
import {createInterface} from 'node:readline';
import {dir,compose,project} from './prepare.mjs';
const root=path.join(dir,'results');
const runs=fs.readdirSync(root,{withFileTypes:true}).filter(e=>e.isDirectory()).flatMap(e=>{
 const folder=path.join(root,e.name),file=path.join(folder,'summary.json');if(!fs.existsSync(file))return [];
 const summary=JSON.parse(fs.readFileSync(file));if(summary.profile!=='submit')return [];
 return [{name:e.name,folder,start:Date.parse(summary.measurementStartedAt),end:Date.parse(summary.measurementFinishedAt),errors:[]}];
});
const since=new Date(Math.min(...runs.map(r=>r.start))).toISOString();
const child=spawn('docker',['compose','-p',project,'-f',compose,'logs','--no-color','--since',since,'checkout-service'],{stdio:['ignore','pipe','pipe']});
let stderr='';child.stderr.on('data',data=>{stderr=(stderr+data).slice(-2000);});
const finished=new Promise((resolve,reject)=>{child.on('error',reject);child.on('close',code=>code===0?resolve():reject(Error(stderr)));});
for await(const line of createInterface({input:child.stdout,crlfDelay:Infinity})){
 let entry;try{entry=JSON.parse(line.slice(line.indexOf('|')+1).trim());}catch{continue;}
 if(entry.level!=='ERROR')continue;
 const timestamp=Date.parse(entry['@timestamp']??entry.timestamp),id=entry.request_id??'';
 const direct=runs.find(r=>id.startsWith(r.name+'-'));
 const run=direct??runs.find(r=>timestamp>=r.start&&timestamp<=r.end);if(!run)continue;
 const causes=[...(entry.stack_trace??'').matchAll(/Caused by: ([A-Za-z0-9_.$]+)/g)].map(m=>m[1]);
 run.errors.push({at:new Date(timestamp).toISOString(),message:entry.message?.slice(0,180),logger:entry.logger_name,association:direct?'request_id':'measurement_timestamp',redisOperation:entry.stack_trace?.match(/redis ([a-z_]+) failed/)?.[1]??null,exceptionClasses:[...new Set(causes)]});
}
await finished;
for(const run of runs){const result={at:new Date().toISOString(),service:'checkout-service',errors:run.errors,note:'Only ERROR log entries associated by request ID or measurement timestamp. Stack payloads/tokens are not exported.'};fs.writeFileSync(path.join(run.folder,'diagnostic-errors.json'),JSON.stringify(result,null,2));console.log(JSON.stringify({name:run.name,errorLogs:run.errors.length,messages:[...new Set(run.errors.map(e=>e.message))]}));}
