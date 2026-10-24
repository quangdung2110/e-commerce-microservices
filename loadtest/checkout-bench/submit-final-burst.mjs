import fs from 'node:fs';
import path from 'node:path';
import {execFileSync} from 'node:child_process';
import {dir} from './prepare.mjs';
const n=Number(process.argv[2]),prefix=process.argv[3]??'submit-20261024-final';
if(!Number.isInteger(n)||n<1||!/^[A-Za-z0-9_-]+$/.test(prefix))throw Error('N prefix');
const catalog=path.join(dir,'results',`${prefix}-suite.json`);if(fs.existsSync(catalog))throw Error('catalog exists');
const results=[];
for(let repeat=1;repeat<=3;repeat++){
 const name=`${prefix}-burst-${n}-r${repeat}`;
 execFileSync(process.execPath,[path.join(dir,'container-run.mjs'),`name=${name}`,`count=${n}`,'profile=submit','promotion=half'],{stdio:'ignore'});
 execFileSync(process.execPath,[path.join(dir,'reconcile.mjs'),name],{stdio:'ignore'});
 const root=path.join(dir,'results',name),s=JSON.parse(fs.readFileSync(path.join(root,'summary.json'))),a=JSON.parse(fs.readFileSync(path.join(root,'reconciliation.json'))),server=JSON.parse(fs.readFileSync(path.join(root,'server-errors.json')));
 const before=JSON.parse(fs.readFileSync(path.join(root,'provider-before.json'))),after=JSON.parse(fs.readFileSync(path.join(root,'provider-after.json'))),webhookFailures=after.webhookFailure-before.webhookFailure;
 const result={name,target:n,sessions:s.sessions,paid:s.paid,requestErrors:s.strict.httpErrors,javaHttp5xx:server.javaHttp5xx,webhookFailures,peak:s.concurrency.peak,p99:s.strict.submitToPaidMs.p99,startLatenessP99:s.startLatenessMs.p99,correctness:a.passed,pass:s.strict.strictZeroErrors&&a.passed&&server.valid&&server.javaHttp5xx===0&&webhookFailures===0&&s.concurrency.peak===n&&s.startLatenessMs.p99<=100};
 results.push(result);fs.writeFileSync(catalog,JSON.stringify({at:new Date().toISOString(),scope:'burst control at sustained candidate after extended warm-up',target:n,results},null,2));console.log(JSON.stringify(result));
 if(a.unresolved.length)execFileSync(process.execPath,[path.join(dir,'cleanup.mjs'),name],{stdio:'ignore'});
 if(a.issues.length)throw Error('Invariant issue');
}
fs.writeFileSync(catalog,JSON.stringify({at:new Date().toISOString(),scope:'burst control at sustained candidate after extended warm-up',target:n,allThreePass:results.every(r=>r.pass),results},null,2));
