// Cancel only unpaid synthetic orders after measurement; preserve the pre-cleanup audit.
import fs from 'node:fs';
import path from 'node:path';
import {createSign,randomUUID}from'node:crypto';
import{dir,generated,sql}from'./prepare.mjs';
const name=process.argv[2];if(!/^[A-Za-z0-9_.-]+$/.test(name??''))throw Error('run name');
const out=path.join(dir,'results',name),orders=JSON.parse(fs.readFileSync(path.join(out,'durable-orders.json')));
const privateKey=fs.readFileSync(path.join(generated,'private.pem'));
const jwt=user=>{const t=Math.floor(Date.now()/1000),enc=x=>Buffer.from(JSON.stringify(x)).toString('base64url');const raw=`${enc({alg:'RS256',kid:'isolated-bench'})}.${enc({iss:'https://auth.ecommerce.local',aud:'ecommerce-api',sub:user,jti:randomUUID(),sid:randomUUID(),roles:['BUYER'],iat:t,nbf:t-1,exp:t+900})}`;return`${raw}.${createSign('RSA-SHA256').update(raw).sign(privateKey,'base64url')}`;};
const attempted=[];
for(const o of orders.filter(o=>o.payment_status!=='PAID')){
 if(!/^\d+$/.test(o.id)||!(/^[a-f0-9-]{36}$/.test(o.user_id)))throw Error('invalid synthetic row');
 const current=sql('order_service',`SELECT status||'|'||payment_status FROM orders WHERE id=${o.id} AND user_id='${o.user_id}'::uuid;`);
 if(current!=='PENDING_PAYMENT|PENDING')continue;
 const r=await fetch(`http://127.0.0.1:38088/api/v1/orders/${o.id}/cancellation-requests`,{method:'POST',headers:{Authorization:`Bearer ${jwt(o.user_id)}`,'content-type':'application/json','Idempotency-Key':randomUUID()},body:JSON.stringify({reason_code:'USER_CANCEL'}),signal:AbortSignal.timeout(15000)});
 const body=await r.json().catch(()=>null);attempted.push({orderId:o.id,status:r.status,operationId:body?.operation_id,code:body?.code});
}
let pending=[];
const end=Date.now()+90000;
do{pending=attempted.filter(o=>sql('order_service',`SELECT status FROM orders WHERE id=${o.orderId}`)!=='CANCELLED');if(!pending.length)break;await new Promise(r=>setTimeout(r,2000));}while(Date.now()<end);
const audit={at:new Date().toISOString(),purpose:'post-measurement cleanup; never a successful payment',attempted,pending};
fs.writeFileSync(path.join(out,'cleanup.json'),JSON.stringify(audit,null,2));console.log(JSON.stringify(audit,null,2));
if(pending.length)process.exitCode=2;
