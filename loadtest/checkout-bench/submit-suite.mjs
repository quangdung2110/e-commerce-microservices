import fs from 'node:fs';
import path from 'node:path';
import {execFileSync} from 'node:child_process';
import {dir} from './prepare.mjs';
const prefix=process.argv[2]??'submit-20261024';
if(!/^[A-Za-z0-9_-]+$/.test(prefix))throw Error('prefix');
const results=[];
const catalog=path.join(dir,'results',`${prefix}-suite.json`);
if(fs.existsSync(catalog))throw Error('suite exists');
function run(name,n,extra=[]){
  execFileSync(process.execPath,[path.join(dir,'container-run.mjs'),`name=${name}`,`count=${n}`,'profile=submit','promotion=half',...extra],{stdio:'ignore'});
  execFileSync(process.execPath,[path.join(dir,'reconcile.mjs'),name],{stdio:'ignore'});
  const p=path.join(dir,'results',name),s=JSON.parse(fs.readFileSync(path.join(p,'summary.json'))),audit=JSON.parse(fs.readFileSync(path.join(p,'reconciliation.json')));
  const serverFile=path.join(p,'server-errors.json'),server=fs.existsSync(serverFile)?JSON.parse(fs.readFileSync(serverFile)):null;
  const providerBefore=JSON.parse(fs.readFileSync(path.join(p,'provider-before.json'))),providerAfter=JSON.parse(fs.readFileSync(path.join(p,'provider-after.json')));
  const webhookFailures=providerAfter.webhookFailure-providerBefore.webhookFailure;
  const result={name,requested:n,started:s.sessions,paid:s.paid,peak:s.concurrency.peak,pass:s.strict.strictZeroErrors&&audit.passed&&s.concurrency.peak===n&&s.startLatenessMs.p99<=100&&(!server||server.valid&&server.javaHttp5xx===0)&&webhookFailures===0,httpErrors:s.strict.httpErrors,http429:s.strict.http429,http5xx:s.strict.http5xx,javaHttp5xx:server?.javaHttp5xx,serverCountersValid:server?.valid,webhookFailures,readyP99:s.readyMs.p99,submitToPaidP99:s.strict.submitToPaidMs.p99,startLatenessP99:s.startLatenessMs.p99,correctness:audit.passed,unresolved:audit.unresolved.length};
  results.push(result);fs.writeFileSync(catalog,JSON.stringify({at:new Date().toISOString(),results},null,2));console.log(JSON.stringify(result));
  if(audit.unresolved.length)execFileSync(process.execPath,[path.join(dir,'cleanup.mjs'),name],{stdio:'ignore'});
  if(audit.issues.length)throw Error('Invariant issue: stop capacity search');
  return result;
}
const warmCount=Number(process.argv[3]??5);run(`${prefix}-warm`,warmCount,warmCount>5?['concurrency=3']:[]);
let low=0,high=0;
const levels=process.argv[4]?process.argv[4].split(',').map(Number):[5,10,20,40,80,160];
if(levels.some(n=>!Number.isInteger(n)||n<1)||levels.some((n,i)=>i&&n<=levels[i-1]))throw Error('levels must increase');
for(const n of levels){
 const r=run(`${prefix}-burst-${n}`,n);if(r.pass)low=n;else{high=n;break;}
}
if(!high)throw Error('No failing upper bound found; expand range explicitly');
while(high-low>1){const n=Math.floor((high+low)/2),r=run(`${prefix}-refine-${n}`,n);if(r.pass)low=n;else high=n;}
let validated=0;
for(let candidate=low;candidate>=1;candidate--){
 let passes=0;
 for(let r=1;r<=3;r++){if(!run(`${prefix}-confirm-${candidate}-r${r}`,candidate).pass)break;passes++;}
 if(passes===3){validated=candidate;break;}
}
fs.writeFileSync(catalog,JSON.stringify({at:new Date().toISOString(),scope:'simultaneous Submit -> client-visible PAID, strict no HTTP errors',highestSinglePass:low,firstFailingBoundary:high,threeRepeatValidated:validated,results},null,2));
console.log(JSON.stringify({highestSinglePass:low,firstFailingBoundary:high,threeRepeatValidated:validated}));
