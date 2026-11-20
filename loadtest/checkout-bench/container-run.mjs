import fs from 'node:fs';
import path from 'node:path';
import {spawn,execFileSync}from'node:child_process';
import{dir,repo,docker,project}from'./prepare.mjs';
import {serverCounters,serverErrorDelta} from './server-counters.mjs';
const args=process.argv.slice(2),name=args.find(x=>x.startsWith('name='))?.slice(5);
if(!name||!/^[A-Za-z0-9_.-]+$/.test(name))throw Error('explicit fresh name required');
const out=path.join(dir,'results',name);if(fs.existsSync(out))throw Error('run exists');
const submitOnly=args.includes('profile=submit');if(submitOnly)args.push('barrier=host');
const container=`${project}-loadgen-${name}`;
const log=fs.openSync(path.join(dir,`${name}.log`),'w');
const child=spawn('docker',['run','--name',container,'--network',`${project}_edge`,'--cpus','2','--memory','3g','-v',`${dir}:/bench`,'-w','/bench','-e','BENCH_CONTAINER_GENERATOR=1','-e','BENCH_BASE_URL=http://gateway','-e','BENCH_PROVIDER_URL=http://provider:8080','node:24-alpine','node','--max-old-space-size=2048','run.mjs',...args],{stdio:['ignore',log,log]});
let done=false,exitCode;child.on('exit',code=>{done=true;exitCode=code;});
while(!done&&!fs.existsSync(path.join(out,'config.json')))await new Promise(r=>setTimeout(r,200));
let monitor;
if(fs.existsSync(out)){
 fs.writeFileSync(path.join(out,'docker-version.json'),docker(['version','--format','{{json .Server}}']));
 fs.writeFileSync(path.join(out,'git-status.txt'),execFileSync('git',['status','--porcelain'],{cwd:repo,encoding:'utf8'}));
 fs.writeFileSync(path.join(out,'git-head.txt'),execFileSync('git',['rev-parse','HEAD'],{cwd:repo,encoding:'utf8'}));
 const state=JSON.parse(docker(['inspect',container]))[0];
 fs.writeFileSync(path.join(out,'generator-container.json'),JSON.stringify({name:container,image:state.Image,limits:{nanoCpus:state.HostConfig.NanoCpus,memory:state.HostConfig.Memory},networks:state.NetworkSettings.Networks},null,2));
 monitor=spawn(process.execPath,[path.join(dir,'monitor.mjs'),out],{stdio:'ignore'});
}
let countersBefore;
while(!done){
 if(submitOnly&&!countersBefore&&fs.existsSync(path.join(out,'setup-summary.json'))){
   countersBefore=serverCounters();fs.writeFileSync(path.join(out,'server-counters-before.json'),JSON.stringify(countersBefore,null,2));
   fs.writeFileSync(path.join(out,'release-measurement'),'');
 }
 await new Promise(r=>setTimeout(r,200));
}
if(countersBefore){const after=serverCounters();fs.writeFileSync(path.join(out,'server-counters-after.json'),JSON.stringify(after,null,2));fs.writeFileSync(path.join(out,'server-errors.json'),JSON.stringify(serverErrorDelta(countersBefore,after),null,2));}
fs.closeSync(log);
if(fs.existsSync(out)){fs.writeFileSync(path.join(out,'stop-monitor'),'');fs.writeFileSync(path.join(out,'generator-exit.json'),docker(['inspect',container,'--format','{{json .State}}']));}
await new Promise(r=>setTimeout(r,6000));monitor?.kill();
docker(['rm',container],{stdio:'ignore'});
if(exitCode!==0)throw Error(`generator exit ${exitCode}; inspect ${name}.log`);
const s=JSON.parse(fs.readFileSync(path.join(out,'summary.json')));
if(submitOnly&&s.concurrencyLimit&&s.sessions<s.count){
 const cursorFile=path.join(dir,'.generated/cursor.json'),cursor=JSON.parse(fs.readFileSync(cursorFile));
 const rows=fs.readFileSync(path.join(out,'sessions.jsonl'),'utf8').trim().split('\n').filter(Boolean).map(JSON.parse);
 const actors=new Set(rows.map(r=>r.actor));
 if(cursor.next!==s.offset+s.count||actors.size!==s.sessions||rows.some(r=>r.actor<0||r.actor>=s.sessions))throw Error('Cannot safely reclaim unused prepared fixtures');
 fs.writeFileSync(cursorFile,JSON.stringify({next:s.offset+s.sessions}));
 fs.writeFileSync(path.join(out,'fixture-pool.json'),JSON.stringify({reserved:s.count,submitted:s.sessions,reclaimedUnused:s.count-s.sessions,note:'Only never-submitted prepared users reclaimed; next Preview supersedes their old PREVIEWED token.'},null,2));
}
console.log(JSON.stringify({name,sessions:s.sessions,paid:s.paid,peak:s.concurrency.peak,funnel:s.funnel,outcomes:s.outcomes,eventLoopP99Ms:s.generator.eventLoopP99Ms,startLatenessP99:s.startLatenessMs.p99},null,2));
