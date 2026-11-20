import fs from 'node:fs';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {gzipSync} from 'node:zlib';
import {dir,repo,generated} from './prepare.mjs';
const destination=path.join(repo,'docs/performance/checkout-payment-baseline-evidence-2026-10-24');
if(fs.existsSync(path.join(destination,'manifest.json')))throw Error('Published evidence already exists; review before replacing');
fs.mkdirSync(destination,{recursive:true});
const manifest={publishedAt:new Date().toISOString(),provider:'synthetic HTTP provider; real internal services',runs:[],files:{}};
const jsonFiles=['summary.json','config.json','artifact-hashes.json','source-hashes.json','harness-hashes.json','container-provenance.json','docker-version.json','provider-before.json','provider-after.json','reconciliation.json','cleanup.json','gateway-exit.json','generator-container.json','generator-exit.json','arrival-evidence.json','terminal-timing.json','funnel.json','resources-summary.json','temporal-diagnostics.json','excluded-pilot.json'];
const samples=['sessions.jsonl','requests.jsonl','metrics.jsonl','docker-stats.jsonl','database.jsonl','provider.jsonl'];
function write(relative,bytes){
  const target=path.join(destination,relative);fs.mkdirSync(path.dirname(target),{recursive:true});fs.writeFileSync(target,bytes);
  manifest.files[relative.replaceAll('\\','/')]={bytes:bytes.length,sha256:createHash('sha256').update(bytes).digest('hex')};
}
const readJsonLines=file=>fs.readFileSync(file,'utf8').trim().split('\n').filter(Boolean).map(JSON.parse);
function checkNoCredentials(raw){if(/-----BEGIN (?:RSA )?PRIVATE KEY-----|eyJ[A-Za-z0-9_-]+\.eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+|pi_[A-Za-z0-9_-]+_secret_[A-Za-z0-9_-]+/.test(raw))throw Error('Unexpected credential in data evidence');}
for(const name of fs.readdirSync(path.join(dir,'results'))){
  const root=path.join(dir,'results',name);if(!fs.existsSync(path.join(root,'summary.json')))continue;
  const s=JSON.parse(fs.readFileSync(path.join(root,'summary.json'))),rows=readJsonLines(path.join(root,'sessions.jsonl')),requests=readJsonLines(path.join(root,'requests.jsonl'));
  if(rows.length!==s.sessions||rows.filter(r=>r.outcome==='paid').length!==s.paid||new Set(rows.map(r=>r.userId)).size!==rows.length)throw Error(`Session accounting mismatch: ${name}`);
  const counts=requests.reduce((o,r)=>{const k=`${r.stage}:${r.status}`;o[k]=(o[k]??0)+1;return o;},{});
  for(const [k,n] of Object.entries(s.requests))if(counts[k]!==n)throw Error(`Request accounting mismatch: ${name} ${k}`);
  if(Object.keys(counts).length!==Object.keys(s.requests).length||requests.length!==s.httpLatencyMs.count)throw Error(`Request total mismatch: ${name}`);
  const first=new Map();for(const r of requests)if(r.stage==='preview'&&!first.has(r.actor))first.set(r.actor,r);
  const firstAttempt={previewAttempts:first.size,previewHttp200:[...first.values()].filter(r=>r.status===200).length,note:'HTTP status only; a truncated 200 body is not proof of a usable quote. Session funnel is the parsed-response measure.'};
  const pilot=fs.existsSync(path.join(root,'excluded-pilot.json'))||['smoke-pilot','smoke-routing','warmup-50'].includes(name);
  const classification=pilot?'setup-pilot-or-warmup':name.startsWith('journey-10000')?'resource-and-generator-diagnostic':s.rate>0?'open-arrivals-local-baseline':'local-cohort-stress';
  manifest.runs.push({name,sessions:s.sessions,clientPaid:s.paid,classification,excludedFromCapacity:pilot||name.startsWith('journey-10000'),firstAttempt});
  for(const file of [...jsonFiles,'git-head.txt'])if(fs.existsSync(path.join(root,file))){const bytes=fs.readFileSync(path.join(root,file));checkNoCredentials(bytes.toString());write(path.join(name,file),bytes);}
  for(const file of samples)if(fs.existsSync(path.join(root,file))){const bytes=fs.readFileSync(path.join(root,file));checkNoCredentials(bytes.toString());write(path.join(name,file+'.gz'),gzipSync(bytes,{level:9}));}
  // Older audits included a checkout token. Always strip it, irrespective of harness version.
  if(fs.existsSync(path.join(root,'durable-orders.json'))){const rows=JSON.parse(fs.readFileSync(path.join(root,'durable-orders.json'))).map(({checkout_token,...row})=>row);write(path.join(name,'durable-orders.json.gz'),gzipSync(JSON.stringify(rows),{level:9}));}
  const snapshot=path.join(root,'harness');if(fs.existsSync(snapshot)){const code=Object.fromEntries(fs.readdirSync(snapshot).filter(n=>n.endsWith('.mjs')).map(n=>[n,fs.readFileSync(path.join(snapshot,n),'utf8')]));write(path.join(name,'harness-snapshot.json.gz'),gzipSync(JSON.stringify(code),{level:9}));}
}
const environment=path.join(generated,'closing-environment.json');if(fs.existsSync(environment)){const raw=fs.readFileSync(environment);checkNoCredentials(raw.toString());write('closing-environment.json',raw);}
fs.writeFileSync(path.join(destination,'manifest.json'),JSON.stringify(manifest,null,2));
console.log(JSON.stringify({destination,runs:manifest.runs.length,files:Object.keys(manifest.files).length,bytes:Object.values(manifest.files).reduce((n,f)=>n+f.bytes,0),accountingVerified:true,credentialScanPassed:true}));
