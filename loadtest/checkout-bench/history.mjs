import fs from 'node:fs';
import path from 'node:path';
import {dir,generated,sql,dc}from'./prepare.mjs';
import {distribution}from'./stats.mjs';
const name=process.argv[2];if(!/^[A-Za-z0-9_.-]+$/.test(name??''))throw Error('run name');
const out=path.join(dir,'results',name),rows=fs.readFileSync(path.join(out,'sessions.jsonl'),'utf8').trim().split('\n').filter(Boolean).map(JSON.parse);
const row=rows.filter(x=>x.readyAt&&/^\d+$/.test(x.orderId)).sort((a,b)=>(b.readyAt-b.submitAt)-(a.readyAt-a.submitAt))[0];
const token=sql('order_service',`SELECT checkout_token FROM orders WHERE id=${row.orderId}`);
dc(['exec','-T','temporal','tctl','--address','temporal:7233','--namespace','default','workflow','show','--workflow_id',`order-checkout-${token}`,'--output_filename','/tmp/checkout-bench-history.json']);
const raw=dc(['exec','-T','temporal','cat','/tmp/checkout-bench-history.json']);
fs.writeFileSync(path.join(generated,'last-history.json'),raw);
const parsed=JSON.parse(raw),events=Array.isArray(parsed)?parsed:parsed.events;
const samples=[];const scheduled=new Map(),started=new Map();
const time=e=>typeof e.eventTime==='string'?Date.parse(e.eventTime):Number(e.eventTime?.seconds??0)*1000+Number(e.eventTime?.nanos??0)/1e6;
for(const e of events){
  if(e.activityTaskScheduledEventAttributes)scheduled.set(String(e.eventId),{type:e.activityTaskScheduledEventAttributes.activityType?.name,at:time(e)});
  if(e.activityTaskStartedEventAttributes){const id=String(e.activityTaskStartedEventAttributes.scheduledEventId);started.set(id,time(e));}
  if(e.activityTaskCompletedEventAttributes){const id=String(e.activityTaskCompletedEventAttributes.scheduledEventId),s=scheduled.get(id);if(s)samples.push({type:s.type,scheduleToStartMs:started.get(id)-s.at,runtimeMs:time(e)-started.get(id)});}
}
const timeline=events.map(e=>({id:e.eventId,type:e.eventType,time:new Date(time(e)).toISOString(),sinceSubmitMs:time(e)-row.submitAt}));
const submission=sql('order_service',`SELECT json_build_object('created_at',created_at,'updated_at',updated_at,'state',state)::text FROM order_checkout_submissions WHERE checkout_token='${token}'::uuid;`);
const result={observedAt:new Date().toISOString(),orderId:row.orderId,readyMs:row.readyAt-row.submitAt,eventCount:events.length,submission:JSON.parse(submission),timeline,samples,activityQueueMs:distribution(samples.map(s=>s.scheduleToStartMs)),activityRuntimeMs:distribution(samples.map(s=>s.runtimeMs))};
fs.writeFileSync(path.join(out,'temporal-diagnostics.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result,null,2));
