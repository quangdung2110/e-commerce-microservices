import fs from 'node:fs';
import path from 'node:path';
import {execFileSync} from 'node:child_process';
import {dir} from './prepare.mjs';
const prefix=process.argv[2]??'submit-steady-20261024',initial=Number(process.argv[3]??10);
if(!/^[A-Za-z0-9_-]+$/.test(prefix)||!Number.isInteger(initial)||initial<1)throw Error('prefix initial');
const results=[],catalog=path.join(dir,'results',`${prefix}-suite.json`);if(fs.existsSync(catalog))throw Error('suite exists');
function run(n,milliseconds,label){
 const name=`${prefix}-${label}-${n}`,count=n*Math.ceil(milliseconds/1000*.6);
 execFileSync(process.execPath,[path.join(dir,'container-run.mjs'),`name=${name}`,`count=${count}`,`concurrency=${n}`,`duration=${milliseconds}`,'profile=submit','promotion=half','failFast=true'],{stdio:'ignore'});
 execFileSync(process.execPath,[path.join(dir,'reconcile.mjs'),name],{stdio:'ignore'});
 const root=path.join(dir,'results',name),s=JSON.parse(fs.readFileSync(path.join(root,'summary.json'))),audit=JSON.parse(fs.readFileSync(path.join(root,'reconciliation.json'))),server=JSON.parse(fs.readFileSync(path.join(root,'server-errors.json')));
 const before=JSON.parse(fs.readFileSync(path.join(root,'provider-before.json'))),after=JSON.parse(fs.readFileSync(path.join(root,'provider-after.json')));
 const webhookFailures=after.webhookFailure-before.webhookFailure;
 const pass=s.strict.strictZeroErrors&&audit.passed&&server.valid&&server.javaHttp5xx===0&&webhookFailures===0&&!s.fixtureExhausted&&!s.stoppedOnError&&s.concurrency.peak===n&&s.concurrency.fractionAtTarget>=.95&&s.generator.eventLoopP99Ms<=100;
 const result={name,target:n,durationMs:milliseconds,started:s.sessions,paid:s.paid,pass,httpErrors:s.strict.httpErrors,javaHttp5xx:server.javaHttp5xx,http429:s.strict.http429,webhookFailures,peak:s.concurrency.peak,mean:s.concurrency.timeWeightedMean,coverage:s.concurrency.fractionAtTarget,measuredMs:s.concurrency.windowMs,submitToPaidP99:s.strict.submitToPaidMs.p99,readyP99:s.readyMs.p99,loopP99:s.generator.eventLoopP99Ms,fixtureExhausted:s.fixtureExhausted,stoppedOnError:s.stoppedOnError,correctness:audit.passed,unresolved:audit.unresolved.length};
 results.push(result);fs.writeFileSync(catalog,JSON.stringify({at:new Date().toISOString(),results},null,2));console.log(JSON.stringify(result));
 if(audit.unresolved.length)execFileSync(process.execPath,[path.join(dir,'cleanup.mjs'),name],{stdio:'ignore'});
 if(audit.issues.length)throw Error('Invariant issue: stop search');
 if(s.fixtureExhausted)throw Error('Fixture pool exhausted; increase count before interpreting capacity');
 return result;
}
let low=0,high=0,n=initial,probe=0;
while(true){const r=run(n,30000,`screen${++probe}`);if(r.pass){low=n;n*=2;if(n>160)throw Error('No failing upper bound; expand explicitly');}else{high=n;break;}}
while(high-low>1){const mid=Math.floor((high+low)/2);if(run(mid,30000,`screen${++probe}`).pass)low=mid;else high=mid;}
let validated=0;
for(let candidate=low;candidate>=1;candidate--){let ok=0;for(let r=1;r<=3;r++){if(!run(candidate,120000,`confirm-r${r}`).pass)break;ok++;}if(ok===3){validated=candidate;break;}}
fs.writeFileSync(catalog,JSON.stringify({at:new Date().toISOString(),scope:'closed-loop Submit -> client-visible PAID without think time',highestScreenPass:low,firstScreenFail:high,threeRepeatValidated:validated,results},null,2));
console.log(JSON.stringify({highestScreenPass:low,firstScreenFail:high,threeRepeatValidated:validated}));
