import fs from 'node:fs';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { sql, generated } from './prepare.mjs';
export const uuid=label=>{const h=createHash('md5').update(label).digest('hex');return `${h.slice(0,8)}-${h.slice(8,12)}-${h.slice(12,16)}-${h.slice(16,20)}-${h.slice(20)}`;};
const users=Number(process.argv[2]??10000);
const startIndex=Number(process.argv[3]??1);
if(!Number.isInteger(users)||users<1||users>500000)throw Error('users out of range');
if(!Number.isInteger(startIndex)||startIndex<1||startIndex>users)throw Error('invalid fresh-fixture start index');
sql('product_service',`BEGIN;
INSERT INTO categories(category_id,parent_id,name,slug) VALUES(md5('bench-category')::uuid,NULL,'Checkout benchmark','checkout-benchmark') ON CONFLICT DO NOTHING;
INSERT INTO products(product_id,category_id,name,slug,description,attributes_json,status,catalog_revision)
SELECT md5('bench-product-'||i)::uuid,md5('bench-category')::uuid,'Checkout item '||i,'checkout-bench-'||i,'Synthetic checkout workload','{}','ACTIVE',1 FROM generate_series(0,2000)i ON CONFLICT DO NOTHING;
INSERT INTO product_skus(sku_id,product_id,sku_code,base_price_vnd,selling_price_vnd,status,variant_attributes_json)
SELECT md5('bench-sku-'||i)::uuid,md5('bench-product-'||i)::uuid,'CHECKOUT-BENCH-'||i,100000,100000,'ACTIVE','{}' FROM generate_series(0,2000)i ON CONFLICT DO NOTHING;
COMMIT; ANALYZE;`);
sql('user_service',`BEGIN;
INSERT INTO "user".user_profiles(id,first_name,last_name) SELECT md5('bench-user-'||i)::uuid,'Bench','Buyer '||i FROM generate_series(1,${users})i ON CONFLICT DO NOTHING;
INSERT INTO "user".user_addresses(id,user_id,is_default,recipient_name,recipient_phone,address_line,province_code,district_code,ward_code)
SELECT md5('bench-address-'||i)::uuid,md5('bench-user-'||i)::uuid,true,'Benchmark Buyer','+84901234567','1 Test Street','79','760','26734' FROM generate_series(1,${users})i ON CONFLICT DO NOTHING;
COMMIT; ANALYZE;`);
sql('inventory_service',`BEGIN;
INSERT INTO warehouse(warehouse_id,name,address,is_active,operational_state,fulfillment_priority,revision,created_at,updated_at)
VALUES('${uuid('bench-warehouse')}','Checkout bench warehouse','{"line1":"1 Test Street","ward":"26734","district":"760","city":"79","country":"VN"}',true,'NORMAL',1,1,now(),now()) ON CONFLICT DO NOTHING;
INSERT INTO stock_position(warehouse_id,sku_id,on_hand,available_to_sell,reserved,committed,quarantine,revision,updated_at)
SELECT '${uuid('bench-warehouse')}',md5('bench-sku-'||i)::uuid::text,1000000000,1000000000,0,0,0,1,now() FROM generate_series(0,2000)i ON CONFLICT DO NOTHING;
COMMIT; ANALYZE;`);
const fixtures=[];
sql('promotion_service',`BEGIN;
INSERT INTO campaigns(code,name,publication_status,start_time,end_time,revision,created_by,updated_by)
VALUES('CHECKOUT-BENCH','Checkout baseline campaign','PUBLISHED',now()-interval '1 hour',now()+interval '2 days',1,'bench','bench') ON CONFLICT DO NOTHING;
INSERT INTO promotions(campaign_id,code,name,distribution_mode,discount_type,discount_value,min_order_amount_vnd,conditions_rule,rule_schema_version,validity_mode,is_enabled)
SELECT id,'CHECKOUT-BENCH-VOUCHER','Benchmark ten thousand VND','PUBLIC_SAVE','FIXED_VND',10000,100000,
'{"node_type":"LOGICAL","operator":"AND","children":[{"node_type":"CONDITION","operator":"GREATER_THAN_OR_EQUAL","field":"cart.total_amount","value":100000}]}'::jsonb,1,'CAMPAIGN_END',true FROM campaigns WHERE code='CHECKOUT-BENCH' ON CONFLICT DO NOTHING;
INSERT INTO promotion_budgets(promotion_id,total_redemption_quantity,total_budget_vnd) SELECT id,1000000,1000000000000 FROM promotions WHERE code='CHECKOUT-BENCH-VOUCHER' ON CONFLICT DO NOTHING;
INSERT INTO user_coupon_wallets(user_id,promotion_id,status,saved_at,expired_at)
SELECT md5('bench-user-'||i)::uuid,p.id,'SAVED',now(),now()+interval '2 days' FROM generate_series(1,${users})i CROSS JOIN promotions p WHERE p.code='CHECKOUT-BENCH-VOUCHER' AND i%2=0 ON CONFLICT DO NOTHING;
COMMIT;`);
const promotionId=sql('promotion_service',"SELECT id FROM promotions WHERE code='CHECKOUT-BENCH-VOUCHER'");
for(let i=1;i<=users;i++){
  const count=i%10<6?1:i%10<9?3:8;
  const indexes=Array.from({length:count},(_,k)=>k===0&&i%10<8?0:1+(i*7+k)%2000);
  fixtures.push({index:i,userId:uuid(`bench-user-${i}`),addressId:uuid(`bench-address-${i}`),skuIds:indexes.map(x=>uuid(`bench-sku-${x}`)),lineCount:count,hot:indexes.includes(0),...(i%2===0?{promotionId}: {})});
}
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
let next=startIndex-1,done=startIndex-1;
await Promise.all(Array.from({length:8},async()=>{while(next<fixtures.length){const f=fixtures[next++];for(const sku of f.skuIds){let okay=false;for(let attempt=0;attempt<30;attempt++){
  let r;try{r=await fetch('http://127.0.0.1:38085/api/v1/carts/mine/items',{method:'POST',headers:{'content-type':'application/json','X-User-Id':f.userId,'Idempotency-Key':uuid(`seed-${f.index}-${sku}`)},body:JSON.stringify({sku_id:sku,delta:1}),signal:AbortSignal.timeout(20000)});}catch{await sleep(3000);continue;}
  const text=await r.text();if(r.ok){okay=true;break;}if(r.status!==503)throw Error(`Cart seed ${r.status}: ${text}`);await sleep(3000);
}if(!okay)throw Error(`Cart seed exhausted for ${f.index}`);}done++;if(done%1000===0)console.log(`Prepared carts ${done}/${users}`);}}));
fs.writeFileSync(path.join(generated,'fixtures.json'),JSON.stringify(fixtures));
console.log(`Seeded ${users} distinct buyers with real Cart API, ${fixtures.reduce((s,f)=>s+f.lineCount,0)} lines.`);
