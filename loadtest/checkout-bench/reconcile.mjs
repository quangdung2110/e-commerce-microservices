import fs from 'node:fs';
import path from 'node:path';
import { dir,sql } from './prepare.mjs';
const name=process.argv[2];if(!name||!/^[A-Za-z0-9_.-]+$/.test(name))throw Error('run name required');
const out=path.join(dir,'results',name);
const rows=fs.readFileSync(path.join(out,'sessions.jsonl'),'utf8').trim().split('\n').filter(Boolean).map(JSON.parse);
if(rows.some(r=>!(/^[0-9a-f-]{36}$/.test(r.userId))))throw Error('invalid fixture identity');
const users=rows.map(r=>`'${r.userId}'::uuid`).join(',');
const query=(db,q)=>JSON.parse(sql(db,`SELECT coalesce(json_agg(s),'[]'::json)::text FROM (${q})s;`));
const orders=query('order_service',`SELECT id::text,user_id::text,checkout_token,status,payment_status,final_amount_minor,total_discount_minor,captured_payment_transaction_id FROM orders WHERE user_id IN (${users})`);
const ids=orders.map(o=>o.id);if(ids.some(id=>!/^\d+$/.test(id)))throw Error('invalid order id');
const inIds=ids.length?ids.join(','):'-1';
const payments=query('payment_service',`SELECT id,order_id::text,user_id::text,status,amount_minor,currency,provider_transaction_id,provider_charge_id,requires_manual_review FROM payment_transactions WHERE order_id IN (${inIds})`);
const journals=query('payment_service',`SELECT p.order_id::text,j.journal_type,count(*)::int n,sum(j.amount_minor)::bigint amount_minor FROM financial_journals j JOIN payment_transactions p ON p.id=j.payment_transaction_id WHERE p.order_id IN (${inIds}) GROUP BY p.order_id,j.journal_type`);
const reservations=query('inventory_service',`SELECT order_id,state FROM order_reservation WHERE order_id IN (${ids.length?ids.map(x=>`'${x}'`).join(','):"'-1'"})`);
const wallets=query('promotion_service',`SELECT user_id::text,used_order_id::text,status,discount_amount_vnd FROM user_coupon_wallets WHERE used_order_id IN (${inIds})`);
const issues=[];
const byUser=new Map(),byOrder=new Map(),journalByOrder=new Map(),reservationByOrder=new Map(reservations.map(r=>[r.order_id,r])),walletByOrder=new Map(wallets.map(w=>[w.used_order_id,w]));
for(const o of orders){const own=byUser.get(o.user_id)??[];own.push(o);byUser.set(o.user_id,own);}
for(const p of payments){const own=byOrder.get(p.order_id)??[];own.push(p);byOrder.set(p.order_id,own);}
for(const p of payments.filter(p=>p.status==='SUCCESS')){
  if(!orders.some(o=>o.id===p.order_id&&o.payment_status==='PAID'))issues.push({orderId:p.order_id,issue:'successful payment without durable PAID Order'});
  if(p.requires_manual_review)issues.push({orderId:p.order_id,issue:'successful happy-path payment requires manual review'});
}
for(const j of journals){const own=journalByOrder.get(j.order_id)??[];own.push(j);journalByOrder.set(j.order_id,own);}
for(const r of rows){const own=byUser.get(r.userId)??[];if(own.length>1)issues.push({userId:r.userId,issue:'duplicate logical order',count:own.length});if(r.outcome==='paid'&&!own.some(o=>o.payment_status==='PAID'))issues.push({userId:r.userId,issue:'client PAID not in durable Order'});}
for(const o of orders.filter(o=>o.payment_status==='PAID')){
  const ps=(byOrder.get(o.id)??[]).filter(p=>p.status==='SUCCESS');
  if(ps.length!==1)issues.push({orderId:o.id,issue:'paid order must have exactly one successful payment',count:ps.length});
  else if(ps[0].amount_minor!==o.final_amount_minor||ps[0].currency!=='VND'||ps[0].user_id!==o.user_id||ps[0].id!==o.captured_payment_transaction_id)issues.push({orderId:o.id,issue:'payment binding/amount mismatch'});
  const js=(journalByOrder.get(o.id)??[]).filter(j=>j.journal_type==='CAPTURE');
  if(js.length!==1||js[0].n!==1||js[0].amount_minor!==o.final_amount_minor)issues.push({orderId:o.id,issue:'capture journal mismatch'});
  if(reservationByOrder.get(o.id)?.state!=='COMMITTED')issues.push({orderId:o.id,issue:'inventory not committed'});
  const wallet=walletByOrder.get(o.id);
  if(o.total_discount_minor>0&&!(wallet?.status==='USED'&&wallet.discount_amount_vnd===o.total_discount_minor))issues.push({orderId:o.id,issue:'promotion not confirmed'});
  if(o.status!=='PROCESSING')issues.push({orderId:o.id,issue:'post-payment not converged',status:o.status});
}
const bucketViolations=Number(sql('inventory_service','SELECT count(*) FROM stock_position WHERE available_to_sell<0 OR reserved<0 OR committed<0 OR quarantine<0 OR on_hand<>available_to_sell+reserved+committed+quarantine;'));
if(bucketViolations)issues.push({issue:'stock bucket invariant',count:bucketViolations});
const lineCounterMismatches=Number(sql('inventory_service',`WITH lines AS(SELECT warehouse_id,sku_id,coalesce(sum(quantity) FILTER(WHERE state='RESERVED'),0) reserved,coalesce(sum(quantity) FILTER(WHERE state='COMMITTED'),0) committed FROM reservation_line GROUP BY 1,2) SELECT count(*) FROM stock_position p LEFT JOIN lines l USING(warehouse_id,sku_id) WHERE p.reserved<>coalesce(l.reserved,0) OR p.committed<>coalesce(l.committed,0);`));
if(lineCounterMismatches)issues.push({issue:'stock counters do not match reservation lines',count:lineCounterMismatches});
const ledgerImbalances=Number(sql('payment_service',`SELECT count(*) FROM(SELECT journal_id FROM financial_ledger_entries GROUP BY journal_id HAVING sum(CASE WHEN direction='DEBIT' THEN amount_minor ELSE -amount_minor END)<>0)s;`));
if(ledgerImbalances)issues.push({issue:'unbalanced journal',count:ledgerImbalances});
const provider=await(await fetch('http://127.0.0.1:38091/bench/stats')).json();
const result={at:new Date().toISOString(),sessions:rows.length,orders:orders.length,clientPaid:rows.filter(r=>r.outcome==='paid').length,durablePaid:orders.filter(o=>o.payment_status==='PAID').length,durableProcessing:orders.filter(o=>o.status==='PROCESSING').length,paymentSuccess:payments.filter(p=>p.status==='SUCCESS').length,inventoryCommitted:reservations.filter(r=>r.state==='COMMITTED').length,captureJournals:journals.filter(j=>j.journal_type==='CAPTURE').reduce((s,j)=>s+j.n,0),bucketViolations,issues,unresolved:orders.filter(o=>!['PROCESSING','CANCELLED'].includes(o.status)).map(o=>({id:o.id,status:o.status,payment_status:o.payment_status})),providerGlobal:provider,exercised:orders.length>0,passed:orders.length>0&&issues.length===0};
fs.writeFileSync(path.join(out,'reconciliation.json'),JSON.stringify(result,null,2));
result.lineCounterMismatches=lineCounterMismatches;
result.ledgerImbalances=ledgerImbalances;
result.passed=result.passed&&result.unresolved.length===0;
fs.writeFileSync(path.join(out,'reconciliation.json'),JSON.stringify(result,null,2));
fs.writeFileSync(path.join(out,'durable-orders.json'),JSON.stringify(orders.map(({checkout_token,...order})=>order),null,2));
console.log(JSON.stringify(result,null,2));
