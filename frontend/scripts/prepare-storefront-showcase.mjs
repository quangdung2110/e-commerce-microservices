/** Local-only, idempotent Product content/media seed. No SKU, price, stock or QA fixture edits. */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';
import { createHash, createHmac } from 'node:crypto';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const collection = JSON.parse(fs.readFileSync(path.join(root, 'frontend/src/content/storefront-collection.json'), 'utf8'));
const assetDir = path.join(root, 'frontend/public/images/showcase');
const evidenceDir = path.join(root, 'docs/frontend/storefront-ux-fix-evidence-2026-10-17');
fs.mkdirSync(assetDir, { recursive: true });

const colors = { ink: '#30302e', edge: '#73726c', paper: '#f0eee6', metal: '#c2c0b6', screen: '#bcd1ca', clay: '#c46849' };
const rect = (x,y,w,h,r,fill,extra='') => `<rect x="${x}" y="${y}" width="${w}" height="${h}" rx="${r}" fill="${fill}" ${extra}/>`;
const c = colors;
const keys = (x,y,rows,columns,w=20,h=16) => Array.from({length:rows},(_,r)=>Array.from({length:columns},(_,i)=>rect(x+i*(w+5),y+r*(h+5),w,h,3,r===0&&i===0?c.clay:c.metal)).join('')).join('');
const drawings = {
  phone: `<g transform="rotate(-12 320 235)">${rect(220,64,200,348,28,c.ink)}${rect(227,71,186,334,23,c.edge)}${rect(234,78,172,320,20,c.screen)}<circle cx="320" cy="234" r="66" fill="${c.paper}"/><circle cx="344" cy="208" r="36" fill="${c.clay}"/>${rect(282,87,76,13,7,c.ink)}${rect(283,379,74,4,2,c.ink)}<circle cx="398" cy="131" r="3" fill="${c.metal}"/></g>`,
  laptop: `<g transform="rotate(-5 320 230)">${rect(137,84,366,240,15,c.edge)}${rect(145,91,350,222,9,c.ink)}${rect(154,100,332,204,4,c.screen)}<path d="M154 245Q280 120 486 185V304H154Z" fill="${c.paper}"/><path d="M154 286Q320 170 486 245V304H154Z" fill="${c.clay}"/><path d="M137 324H503L564 382Q568 394 548 396H92Q72 394 76 382Z" fill="${c.metal}"/>${keys(166,333,2,12,20,10)}${rect(270,369,100,18,4,c.edge)}<path d="M94 397H547" stroke="${c.edge}" stroke-width="6"/></g>`,
  earbuds: `<g>${rect(185,243,270,139,55,c.metal)}${rect(190,240,260,126,52,'#faf9f5')}<path d="M193 285H447" stroke="${c.metal}" stroke-width="3"/><circle cx="320" cy="313" r="4" fill="${c.clay}"/>${rect(294,354,52,6,3,c.edge)}<g transform="rotate(-22 242 167)">${rect(224,139,34,110,16,c.metal)}${rect(215,111,65,65,29,'#faf9f5')}${rect(255,125,26,32,12,c.ink)}<circle cx="235" cy="143" r="5" fill="${c.edge}"/></g><g transform="rotate(24 395 167)">${rect(378,139,34,110,16,c.metal)}${rect(360,111,65,65,29,'#faf9f5')}${rect(360,125,26,32,12,c.ink)}<circle cx="405" cy="143" r="5" fill="${c.edge}"/></g></g>`,
  tablet: `<g transform="rotate(9 320 240)">${rect(158,66,324,348,22,c.edge)}${rect(164,72,312,336,18,c.ink)}${rect(179,89,282,302,8,c.screen)}${rect(195,109,132,110,8,c.paper)}${rect(339,109,104,110,8,c.clay)}${rect(195,232,248,138,8,c.paper)}<path d="M210 340L260 282L300 314L350 263L428 345" fill="none" stroke="${c.edge}" stroke-width="8" stroke-linejoin="round"/><circle cx="320" cy="80" r="3" fill="${c.metal}"/>${rect(493,105,9,262,4,c.metal)}</g>`,
  watch: `<g transform="rotate(-12 320 240)">${rect(270,28,100,424,30,c.metal)}${rect(280,35,80,409,24,c.edge)}${rect(220,122,200,234,45,c.metal)}${rect(229,130,182,218,38,c.ink)}<circle cx="320" cy="238" r="66" fill="none" stroke="${c.screen}" stroke-width="12"/><path d="M320 172A66 66 0 0 1 383 259" fill="none" stroke="${c.clay}" stroke-width="12"/><path d="M320 193V240L350 259" fill="none" stroke="${c.paper}" stroke-width="6" stroke-linecap="round"/>${rect(418,180,10,36,4,c.edge)}${rect(308,383,24,6,3,c.ink)}${rect(308,402,24,6,3,c.ink)}</g>`,
  charger: `<g transform="rotate(-10 320 240)">${rect(238,81,27,91,6,c.metal)}${rect(350,81,27,91,6,c.metal)}${rect(194,154,254,224,28,c.metal)}${rect(194,154,230,208,28,'#faf9f5')}<path d="M424 180L448 167V351L424 366" fill="${c.edge}"/>${rect(230,206,67,25,10,c.ink)}${rect(320,206,67,25,10,c.ink)}${rect(242,213,43,8,4,c.clay)}${rect(332,213,43,8,4,c.screen)}<path d="M324 267L300 307H326L310 341L348 293H322Z" fill="${c.edge}"/></g>`,
  keyboard: `<g transform="rotate(-8 320 240)">${rect(85,142,470,214,22,c.edge)}${rect(85,136,470,208,18,c.ink)}${keys(106,158,5,15,23,24)}${rect(207,311,185,20,4,c.metal)}<circle cx="527" cy="160" r="9" fill="${c.clay}"/><path d="M121 139Q90 103 159 76" fill="none" stroke="${c.edge}" stroke-width="7"/></g>`,
  camera: `<g>${rect(238,324,164,53,20,c.metal)}<ellipse cx="320" cy="377" rx="108" ry="22" fill="${c.metal}"/><ellipse cx="320" cy="365" rx="108" ry="22" fill="#faf9f5"/>${rect(233,111,174,238,75,'#faf9f5')}<path d="M385 153Q432 248 370 326" fill="none" stroke="${c.metal}" stroke-width="16"/><circle cx="320" cy="211" r="70" fill="${c.ink}"/><circle cx="320" cy="211" r="52" fill="${c.edge}"/><circle cx="320" cy="211" r="33" fill="${c.ink}"/><circle cx="308" cy="198" r="12" fill="${c.screen}"/><circle cx="320" cy="306" r="5" fill="${c.clay}"/></g>`,
};

for (const item of collection.products) {
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="960" height="720" viewBox="0 0 640 480" role="img"><title>Ảnh minh họa sản phẩm trình diễn</title>${rect(0,0,640,480,0,c.paper)}<circle cx="333" cy="225" r="178" fill="#e8e6dc"/><ellipse cx="320" cy="409" rx="171" ry="17" fill="${c.ink}" opacity=".08"/>${drawings[item.illustration]}</svg>`;
  fs.writeFileSync(path.join(assetDir, `${item.illustration}.svg`), svg);
}

const gateway = process.env.GATEWAY ?? 'http://localhost:8088';
if (!['localhost','127.0.0.1','[::1]'].includes(new URL(gateway).hostname)) throw new Error('Local gateway required.');
if (!process.argv.includes('--apply')) {
  console.log(JSON.stringify({ mode:'dry-run', assets:collection.products.length, slugs:collection.products.map(p=>p.slug) }));
  process.exit(0);
}

let token;
async function api(route, { method='GET', body, etag }={}) {
  const response = await fetch(gateway+route,{method,signal:AbortSignal.timeout(20000),headers:{'Content-Type':'application/json',...(token?{Authorization:`Bearer ${token}`} : {}),...(etag?{'If-Match':etag}:{})},...(body===undefined?{}:{body:JSON.stringify(body)})});
  const data=await response.json().catch(()=>null);
  if(!response.ok)throw new Error(`${method} ${route}: ${response.status} ${data?.code??''}`);
  return {data,etag:response.headers.get('etag')};
}
const minioEnv=Object.fromEntries(JSON.parse(execFileSync('docker',['inspect','minio','--format','{{json .Config.Env}}'],{encoding:'utf8'})).map(v=>[v.slice(0,v.indexOf('=')),v.slice(v.indexOf('=')+1)]));
async function upload(key,bytes) {
  const sha=value=>createHash('sha256').update(value).digest('hex');
  const hmac=(key,value)=>createHmac('sha256',key).update(value).digest();
  const uri='/product-media/'+key,stamp=new Date().toISOString().replace(/[:-]|\.\d{3}/g,''),day=stamp.slice(0,8),scope=day+'/us-east-1/s3/aws4_request',hash=sha(bytes);
  const canonical=`PUT\n${uri}\n\nhost:localhost:9000\nx-amz-content-sha256:${hash}\nx-amz-date:${stamp}\n\nhost;x-amz-content-sha256;x-amz-date\n${hash}`;
  const signingKey=hmac(hmac(hmac(hmac('AWS4'+minioEnv.MINIO_ROOT_PASSWORD,day),'us-east-1'),'s3'),'aws4_request');
  const signature=createHmac('sha256',signingKey).update(`AWS4-HMAC-SHA256\n${stamp}\n${scope}\n${sha(canonical)}`).digest('hex');
  const r=await fetch('http://localhost:9000'+uri,{method:'PUT',headers:{'Content-Type':'image/svg+xml','x-amz-content-sha256':hash,'x-amz-date':stamp,Authorization:`AWS4-HMAC-SHA256 Credential=${minioEnv.MINIO_ROOT_USER}/${scope}, SignedHeaders=host;x-amz-content-sha256;x-amz-date, Signature=${signature}`},body:bytes});
  if(!r.ok)throw new Error(`Media upload ${r.status}`);
}

token=(await api('/api/v1/auth/sessions',{method:'POST',body:{email:process.env.ADMIN_EMAIL??'admin@technova.test',password:process.env.ADMIN_PASSWORD??'Terra-Local-Pass-2026'}})).data.access_token;
if(!token)throw new Error('No admin session.');
const summaries=[];
let cursor;
do {const page=(await api('/api/v1/products?limit=100'+(cursor?'&cursor='+encodeURIComponent(cursor):''))).data;summaries.push(...page.items);cursor=page.next_cursor;}while(cursor);
const snapshots=[],changes=[];
for(const item of collection.products) {
  const summary=summaries.find(p=>p.slug===item.slug);
  if(!summary)throw new Error(`Missing canonical product ${item.slug}; run the existing catalog seed first.`);
  const current=await api(`/api/v1/products/${summary.product_id}`);
  snapshots.push(current.data);
}
fs.mkdirSync(evidenceDir,{recursive:true});
const backup=path.join(evidenceDir,'catalog-before.json');
if(!fs.existsSync(backup))fs.writeFileSync(backup,JSON.stringify(snapshots,null,2));

for(const item of collection.products) {
  const summary=summaries.find(p=>p.slug===item.slug);
  const route=`/api/v1/products/${summary.product_id}`;
  let current=await api(route);
  const p=current.data,key=`showcase-v1/${item.illustration}.svg`;
  const description=item.copy+'\n\nSản phẩm thuộc bộ dữ liệu trình diễn TechNova; hình ảnh là minh họa, không phải ảnh chụp thiết bị thương mại.';
  if(p.description!==description) {await api(route,{method:'PUT',etag:current.etag,body:{category_id:p.category_id,name:p.name,slug:p.slug,description,attributes:p.attributes}});current=await api(route);}
  await upload(key,fs.readFileSync(path.join(assetDir,item.illustration+'.svg')));
  if(!current.data.media.some(m=>m.storage_key===key&&m.sku_id===null)) {
    const created=await api(route+'/media',{method:'POST',etag:current.etag,body:{media_type:'IMAGE',storage_key:key,alt_text:'Ảnh minh họa '+p.name,sort_order:0}});
    changes.push({slug:item.slug,media_id:created.data.media_id,key});
  }
  console.log(JSON.stringify({slug:item.slug,description:'updated',media:key}));
}
const journalPath=path.join(evidenceDir,'catalog-changes.json');
const journal=fs.existsSync(journalPath)?JSON.parse(fs.readFileSync(journalPath,'utf8')):[];
fs.writeFileSync(journalPath,JSON.stringify([...journal,...changes.filter(change=>!journal.some(previous=>previous.media_id===change.media_id))],null,2));
console.log(JSON.stringify({products:collection.products.length,media_created:changes.length,prices_changed:0,skus_changed:0,fixtures_deleted:0}));
