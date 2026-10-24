/** Local-only metadata migration. No deletion, SKU/code/price/status/stock changes. */
import fs from 'node:fs';
import {fileURLToPath} from 'node:url';
import {seedProducts,specificationNames,structuredVariant} from './catalog-specifications.mjs';
const gateway=process.env.GATEWAY??'http://localhost:8088';
if(!['localhost','127.0.0.1','[::1]'].includes(new URL(gateway).hostname)) throw new Error('Local gateway required.');
const apply=process.argv.includes('--apply'),restore=process.argv.includes('--restore');
if(apply&&restore)throw new Error('Choose --apply or --restore.');
const evidence=fileURLToPath(new URL('../../docs/frontend/catalog-filter-fix-evidence-2026-10-17/',import.meta.url));
let token;
async function api(route,{method='GET',body,etag}={}) {
  const response=await fetch(gateway+route,{method,signal:AbortSignal.timeout(20000),headers:{'Content-Type':'application/json',...(token?{Authorization:`Bearer ${token}`} : {}),...(etag?{'If-Match':etag}:{})},...(body===undefined?{}:{body:JSON.stringify(body)})});
  const data=await response.json().catch(()=>null);
  if(!response.ok)throw new Error(`${method} ${route}: ${response.status} ${data?.code??''}`);
  return {data,etag:response.headers.get('etag')};
}
token=(await api('/api/v1/auth/sessions',{method:'POST',body:{email:process.env.ADMIN_EMAIL??'admin@technova.test',password:process.env.ADMIN_PASSWORD??'Terra-Local-Pass-2026'}})).data.access_token;
const categories=(await api('/api/v1/categories')).data.items;
const attributes=(await api('/api/v1/attributes')).data.items;
let cursor; const products=[];
do {const page=(await api('/api/v1/products?limit=100'+(cursor?'&cursor='+encodeURIComponent(cursor):''))).data; products.push(...page.items);cursor=page.next_cursor;}while(cursor);
const before=[],plans=[];
const sameValues=(a,b)=>JSON.stringify(Object.entries(a).sort(([x],[y])=>x.localeCompare(y)))===JSON.stringify(Object.entries(b).sort(([x],[y])=>x.localeCompare(y)));
for(const [categorySlug,slugs] of Object.entries(seedProducts)) {
  const category=categories.find(c=>c.slug===categorySlug);
  if(!category)throw new Error(`Missing seed category ${categorySlug}`);
  for(const slug of slugs) {
    const summary=products.find(p=>p.slug===slug);
    if(!summary)throw new Error(`Missing seed product ${slug}`);
    const p=(await api(`/api/v1/products/${summary.product_id}`)).data;
    if(p.category_id!==category.category_id)throw new Error(`Category mismatch ${slug}`);
    const variants=p.skus.map(sku=>({sku,attributes:structuredVariant(slug,sku.variant_attributes)}));
    const identities=variants.map(v=>JSON.stringify(Object.entries(v.attributes).sort(([a],[b])=>a.localeCompare(b))));
    if(new Set(identities).size!==identities.length)throw new Error(`Ambiguous variants ${slug}`);
    before.push(p); plans.push({category,p,variants});
  }
}
if(process.argv.includes('--verify')) {
  const snapshot=JSON.parse(fs.readFileSync(evidence+'catalog-before.json','utf8'));
  const checks=plans.map(({p})=>{
    const previous=snapshot.products.find(old=>old.product_id===p.product_id);
    const axes=new Set(p.skus.flatMap(s=>Object.keys(s.variant_attributes)));
    return {slug:p.slug,sku_count:p.skus.length,identities_prices_status_preserved:previous.skus.length===p.skus.length&&previous.skus.every(old=>p.skus.some(now=>['sku_id','sku_code','base_price_vnd','selling_price_vnd','status'].every(key=>old[key]===now[key]))),legacy_configuration_removed:p.skus.every(s=>!Object.hasOwn(s.variant_attributes,'cau-hinh')),variant_axes_complete:p.skus.every(s=>[...axes].every(code=>Object.hasOwn(s.variant_attributes,code)))};
  });
  fs.writeFileSync(evidence+'catalog-verification.json',JSON.stringify(checks,null,2));
  if(checks.some(c=>!c.identities_prices_status_preserved||!c.legacy_configuration_removed||!c.variant_axes_complete))throw new Error('Catalog verification failed; inspect evidence.');
  console.log(JSON.stringify({verified_products:checks.length,verified_skus:checks.reduce((sum,c)=>sum+c.sku_count,0),all_checks_passed:true}));process.exit(0);
}
if(!apply&&!restore) { console.log(JSON.stringify({mode:'dry-run',products:plans.length,sku_metadata_changes:plans.flatMap(p=>p.variants).filter(v=>!sameValues(v.sku.variant_attributes,v.attributes)).length,prices_changed:0}));process.exit(0); }
fs.mkdirSync(evidence,{recursive:true});
const backup=evidence+'catalog-before.json';
if(!restore&&!fs.existsSync(backup))fs.writeFileSync(backup,JSON.stringify({categories,attributes,products:before},null,2));
let updated=0;
if(restore) {
  const snapshot=JSON.parse(fs.readFileSync(backup,'utf8'));
  for(const p of snapshot.products)for(const sku of p.skus) {
    const route=`/api/v1/products/${p.product_id}`,current=await api(route),now=current.data.skus.find(s=>s.sku_id===sku.sku_id);
    if(!now)throw new Error(`SKU missing since snapshot: ${sku.sku_id}`);
    if(sameValues(now.variant_attributes,sku.variant_attributes))continue;
    if(!sameValues(now.variant_attributes,structuredVariant(p.slug,sku.variant_attributes)))throw new Error(`SKU metadata changed since migration: ${sku.sku_id}; review before restoring.`);
    await api(route+'/skus/'+sku.sku_id,{method:'PUT',etag:current.etag,body:{sku_code:now.sku_code,base_price_vnd:now.base_price_vnd,status:now.status,variant_attributes:sku.variant_attributes}});updated++;
  }
} else {
  for(const [code,name] of Object.entries(specificationNames)) if(!attributes.some(a=>a.code===code)) attributes.push((await api('/api/v1/attributes',{method:'POST',body:{code,name,value_type:'TEXT'}})).data);
  for(const attribute of attributes.filter(a=>Object.hasOwn(specificationNames,a.code))) if(attribute.value_type!=='TEXT')throw new Error(`Incompatible attribute type ${attribute.code}`);
  const mapped=new Set();
  for(const {category,p,variants} of plans) {
    for(const code of new Set(variants.flatMap(v=>Object.keys(v.attributes)).filter(code=>Object.hasOwn(specificationNames,code)))) {
      const a=attributes.find(a=>a.code===code),key=category.category_id+':'+code;
      if(mapped.has(key))continue;
      const mapping=category.attributes.find(m=>m.attribute_id===a.attribute_id);
      if(mapping&&(!mapping.is_variant||mapping.is_required))throw new Error(`Incompatible mapping ${key}`);
      if(!mapping)await api(`/api/v1/categories/${category.category_id}/attributes/${a.attribute_id}`,{method:'PUT',body:{is_required:false,is_variant:true}});
      mapped.add(key);
    }
    for(const v of variants) {
      if(sameValues(v.sku.variant_attributes,v.attributes))continue;
      const route=`/api/v1/products/${p.product_id}`,current=await api(route),sku=current.data.skus.find(s=>s.sku_id===v.sku.sku_id);
      await api(route+'/skus/'+sku.sku_id,{method:'PUT',etag:current.etag,body:{sku_code:sku.sku_code,base_price_vnd:sku.base_price_vnd,status:sku.status,variant_attributes:v.attributes}});updated++;
    }
  }
}
console.log(JSON.stringify({mode:restore?'restore':'apply',products:plans.length,sku_metadata_updated:updated,prices_changed:0,fixtures_deleted:0}));
