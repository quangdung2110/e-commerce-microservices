import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import {gzipSync,gunzipSync} from 'node:zlib';
import {createHash} from 'node:crypto';
import {dir,repo} from './prepare.mjs';
import {submitChecks,submitConcurrency} from './submit-stats.mjs';
const root=path.join(repo,'docs/performance/submit-paid-capacity-evidence-2026-10-24');
const verify=process.argv.includes('--verify');
const digest=bytes=>createHash('sha256').update(bytes).digest('hex');
if(verify){
 const manifest=JSON.parse(fs.readFileSync(path.join(root,'manifest.json')));
 for(const [relative,value]of Object.entries(manifest.files)){
  const target=path.resolve(root,relative);assert.ok(target.startsWith(root+path.sep));
  const bytes=fs.readFileSync(target);assert.equal(bytes.length,value.bytes,relative);assert.equal(digest(bytes),value.sha256,relative);
 }
 for(const run of manifest.runs){
  const folder=path.join(root,run.name),readLines=file=>gunzipSync(fs.readFileSync(path.join(folder,file+'.gz'))).toString().trim().split('\n').filter(Boolean).map(JSON.parse);
  const s=JSON.parse(fs.readFileSync(path.join(folder,'summary.json'))),rows=readLines('sessions.jsonl'),requests=readLines('requests.jsonl');
  assert.deepEqual(submitChecks(rows,requests),s.strict,run.name+' strict');
  assert.deepEqual(submitConcurrency(rows,s.concurrency.windowStart,s.concurrency.windowEnd,s.concurrency.target),s.concurrency,run.name+' concurrency');
 }
 console.log(JSON.stringify({checksumFiles:Object.keys(manifest.files).length,recomputedRuns:manifest.runs.length,pass:true}));
}else{
 if(fs.existsSync(path.join(root,'manifest.json')))throw Error('Evidence already published; do not silently replace it');
 fs.mkdirSync(root,{recursive:true});
 const manifest={at:new Date().toISOString(),scope:'Submit -> client-visible PAID; zero observed request, Java HTTP 5xx, webhook and reconciliation errors',provider:'HTTP simulator; real internal services',runs:[],files:{}};
 const meta=['summary.json','config.json','setup-summary.json','measurement-start.json','artifact-hashes.json','source-hashes.json','harness-hashes.json','docker-version.json','container-provenance.json','generator-container.json','generator-exit.json','provider-before.json','provider-after.json','server-counters-before.json','server-counters-after.json','server-errors.json','fixture-pool.json','reconciliation.json','cleanup.json','resources-summary.json','diagnostic-errors.json','git-head.txt'];
 const samples=['sessions.jsonl','requests.jsonl','setup-requests.jsonl','metrics.jsonl','docker-stats.jsonl','database.jsonl','provider.jsonl'];
 const credentials=/-----BEGIN (?:RSA )?PRIVATE KEY-----|eyJ[A-Za-z0-9_-]+\.eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+|pi_[A-Za-z0-9_-]+_secret_[A-Za-z0-9_-]+/;
 function write(relative,raw,{code=false,compress=false}={}){
  const bytes=Buffer.isBuffer(raw)?raw:Buffer.from(raw);if(!code)assert.ok(!credentials.test(bytes.toString()),'credential in '+relative);
  const output=compress?gzipSync(bytes,{level:9}):bytes,target=path.join(root,relative);fs.mkdirSync(path.dirname(target),{recursive:true});fs.writeFileSync(target,output);
  manifest.files[relative.replaceAll('\\','/')]={bytes:output.length,sha256:digest(output)};
 }
 const resultsRoot=path.join(dir,'results');
 for(const entry of fs.readdirSync(resultsRoot,{withFileTypes:true})){
  if(!entry.isDirectory())continue;
  const name=entry.name,folder=path.join(resultsRoot,name),file=path.join(folder,'summary.json');if(!fs.existsSync(file))continue;
  const s=JSON.parse(fs.readFileSync(file));if(s.profile!=='submit')continue;
  const rows=fs.readFileSync(path.join(folder,'sessions.jsonl'),'utf8').trim().split('\n').filter(Boolean).map(JSON.parse),requests=fs.readFileSync(path.join(folder,'requests.jsonl'),'utf8').trim().split('\n').filter(Boolean).map(JSON.parse);
  assert.equal(rows.length,s.sessions);assert.equal(new Set(rows.map(r=>r.userId)).size,rows.length);
  assert.ok(requests.every(r=>!r.stage.startsWith('setup_')));
  assert.deepEqual(submitChecks(rows,requests),s.strict,name+' strict');
  assert.deepEqual(submitConcurrency(rows,s.concurrency.windowStart,s.concurrency.windowEnd,s.concurrency.target),s.concurrency,name+' concurrency');
  manifest.runs.push({name,classification:name.endsWith('-warm')?'warmup':s.durationMs?'closed-loop':'burst',sessions:s.sessions,paid:s.paid,strictZeroRequestErrors:s.strict.strictZeroErrors,auditAvailable:fs.existsSync(path.join(folder,'reconciliation.json'))});
  for(const file of meta)if(fs.existsSync(path.join(folder,file)))write(path.join(name,file),fs.readFileSync(path.join(folder,file)));
  for(const file of samples)if(fs.existsSync(path.join(folder,file)))write(path.join(name,file+'.gz'),fs.readFileSync(path.join(folder,file)),{compress:true});
  const orders=path.join(folder,'durable-orders.json');if(fs.existsSync(orders))write(path.join(name,'durable-orders.json.gz'),JSON.stringify(JSON.parse(fs.readFileSync(orders)).map(({checkout_token,...r})=>r)),{compress:true});
  const code=path.join(folder,'harness');if(fs.existsSync(code))write(path.join(name,'harness-snapshot.json.gz'),JSON.stringify(Object.fromEntries(fs.readdirSync(code).filter(n=>n.endsWith('.mjs')).map(n=>[n,fs.readFileSync(path.join(code,n),'utf8')]))),{compress:true,code:true});
 }
 for(const name of fs.readdirSync(resultsRoot).filter(n=>/^submit-.*-suite\.json$/.test(n)))write(name,fs.readFileSync(path.join(resultsRoot,name)));
 fs.writeFileSync(path.join(root,'manifest.json'),JSON.stringify(manifest,null,2));
 console.log(JSON.stringify({runs:manifest.runs.length,files:Object.keys(manifest.files).length,bytes:Object.values(manifest.files).reduce((n,f)=>n+f.bytes,0)}));
}
