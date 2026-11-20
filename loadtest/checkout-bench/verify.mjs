import fs from 'node:fs';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {gunzipSync} from 'node:zlib';
import assert from 'node:assert/strict';
import {repo,dir} from './prepare.mjs';
import {summarize} from './stats.mjs';
const root=path.join(repo,'docs/performance/checkout-payment-baseline-evidence-2026-10-24');
const manifest=JSON.parse(fs.readFileSync(path.join(root,'manifest.json')));
for(const [name,expected] of Object.entries(manifest.files)){
  const target=path.resolve(root,name);assert.ok(target.startsWith(root+path.sep),'Evidence path must stay within bundle');
  const bytes=fs.readFileSync(target);assert.equal(bytes.length,expected.bytes,name);
  assert.equal(createHash('sha256').update(bytes).digest('hex'),expected.sha256,name);
  const raw=(name.endsWith('.gz')?gunzipSync(bytes):bytes).toString();
  if(name.includes('.jsonl'))for(const line of raw.trim().split('\n').filter(Boolean))JSON.parse(line);
  else if(name.includes('.json'))JSON.parse(raw);
}
const lines=(run,name)=>gunzipSync(fs.readFileSync(path.join(root,run,name+'.gz'))).toString().trim().split('\n').filter(Boolean).map(JSON.parse);
for(const run of manifest.runs){
  const rows=lines(run.name,'sessions.jsonl'),requests=lines(run.name,'requests.jsonl');
  const expected=JSON.parse(fs.readFileSync(path.join(root,run.name,'summary.json'))),actual=summarize(rows,requests);
  for(const key of ['sessions','paid','concurrency','readyMs','machineMs','requests'])assert.deepEqual(actual[key],expected[key],run.name+' '+key);
}
const documents=[path.join(dir,'README.md'),path.join(root,'README.md'),path.join(root,'../checkout-payment-10000-workload.md'),path.join(root,'../checkout-payment-baseline-2026-10-24.md')];
for(const file of documents)for(const match of fs.readFileSync(file,'utf8').matchAll(/\[[^\]]*\]\(([^)]+)\)/g)){
  const target=match[1];if(/^(https?:|#)/.test(target))continue;
  assert.ok(fs.existsSync(path.resolve(path.dirname(file),target.split('#')[0])),file+' → '+target);
}
console.log(JSON.stringify({checksumFiles:Object.keys(manifest.files).length,runsRecomputed:manifest.runs.length,documentLinks:'pass'}));
