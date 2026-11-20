import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { dir } from './prepare.mjs';
const steps=[['open-1',120,1,'machine'],['open-5',600,5,'machine'],['open-10',1200,10,'machine'],['open-25',3000,25,'machine'],['journey-100',100,0,'journey'],['journey-1000',1000,0,'journey'],['journey-10000',10000,0,'journey']];
const select=process.argv.slice(2);
for(const[name,count,rate,profile]of steps.filter(x=>!select.length||select.includes(x[0]))){
  console.log(`Start ${name}: ${count} distinct buyers, ${rate||'burst'} arrivals/s, ${profile}, 50% voucher`);
  const log=fs.openSync(path.join(dir,`${name}.log`),'w');
  try{execFileSync(process.execPath,[path.join(dir,'run.mjs'),`name=${name}`,`count=${count}`,`rate=${rate}`,`profile=${profile}`,'promotion=half'],{stdio:['ignore',log,log]});}finally{fs.closeSync(log);}
  execFileSync(process.execPath,[path.join(dir,'reconcile.mjs'),name],{stdio:'ignore'});
  const p=path.join(dir,'results',name),s=JSON.parse(fs.readFileSync(path.join(p,'summary.json'))),r=JSON.parse(fs.readFileSync(path.join(p,'reconciliation.json')));
  console.log(JSON.stringify({name,paid:s.paid,total:s.sessions,peak:s.concurrency.peak,readyP99:s.readyMs.p99,machineP99:s.machineMs.p99,outcomes:s.outcomes,unresolved:r.unresolved.length,correctness:r.passed}));
  if(r.unresolved.length){console.log('Unresolved durable work requires review before the next independent stage.');process.exitCode=2;break;}
}
