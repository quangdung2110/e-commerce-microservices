import fs from 'node:fs';
import path from 'node:path';
import {execFileSync} from 'node:child_process';
import {dir} from './prepare.mjs';
const rate=Number(process.argv[2]??2),count=Number(process.argv[3]??240),repeats=Number(process.argv[4]??3);
if(!(rate>0&&count>=1&&Number.isInteger(count)&&repeats>=1&&repeats<=10&&Number.isInteger(repeats)))throw Error('rate count repeats');
for(let i=1;i<=repeats;i++){
  const name=`open-${rate}-linux-r${i}`;
  execFileSync(process.execPath,[path.join(dir,'container-run.mjs'),`name=${name}`,`count=${count}`,`rate=${rate}`,'profile=machine','promotion=half'],{stdio:'ignore'});
  execFileSync(process.execPath,[path.join(dir,'reconcile.mjs'),name],{stdio:'ignore'});
  const root=path.join(dir,'results',name),s=JSON.parse(fs.readFileSync(path.join(root,'summary.json'))),r=JSON.parse(fs.readFileSync(path.join(root,'reconciliation.json')));
  console.log(JSON.stringify({name,paid:s.paid,total:s.sessions,readyP99:s.readyMs.p99,machineP99:s.machineMs.p99,latenessP99:s.startLatenessMs.p99,outcomes:s.outcomes,correctness:r.passed,unresolved:r.unresolved.length}));
  if(r.unresolved.length)throw Error('Unresolved work: review before next independent repetition');
}
