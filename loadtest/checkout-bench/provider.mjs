import http from 'node:http';
import fs from 'node:fs';
import { createHmac, randomUUID } from 'node:crypto';
import { monitorEventLoopDelay } from 'node:perf_hooks';
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const intents=new Map(), keys=new Map(), deliveries=new Map();
const delay=monitorEventLoopDelay({resolution:20}); delay.enable();
const counters={create:0,createReplay:0,confirm:0,retrieve:0,webhookAttempt:0,webhookSuccess:0,webhookFailure:0};
const timings={create:100,confirm:200,retrieve:50};
const json=(res,status,value)=>{res.writeHead(status,{'content-type':'application/json','request-id':`req_${randomUUID().replaceAll('-','')}`});res.end(JSON.stringify(value));};
async function webhook(intent) {
  const eid=`evt_${intent.id.slice(3)}`;
  if(deliveries.get(eid)?.done)return;
  const event={id:eid,object:'event',api_version:'2026-08-26.dahlia',created:Math.floor(Date.now()/1000),livemode:false,type:'payment_intent.succeeded',data:{object:intent},pending_webhooks:1};
  const payload=JSON.stringify(event);
  for(let attempt=0;attempt<8;attempt++) {
    counters.webhookAttempt++;
    const t=Math.floor(Date.now()/1000),sig=createHmac('sha256','whsec_isolated_benchmark').update(`${t}.${payload}`).digest('hex');
    try {const r=await fetch(process.env.BENCH_WEBHOOK_URL,{method:'POST',headers:{'content-type':'application/json','Stripe-Signature':`t=${t},v1=${sig}`},body:payload,signal:AbortSignal.timeout(10000)}); await r.text(); if(r.ok){counters.webhookSuccess++;deliveries.set(eid,{done:true});return;}}
    catch {}
    counters.webhookFailure++;await sleep(Math.min(1000*2**attempt,10000));
  }
  deliveries.set(eid,{done:false});
}
http.createServer(async(req,res)=>{
  try {
    const url=new URL(req.url,'http://provider');
    if(url.pathname==='/.well-known/jwks.json') return json(res,200,JSON.parse(fs.readFileSync('/bench/.generated/jwks.json')));
    if(url.pathname==='/health') return json(res,200,{status:'UP'});
    if(url.pathname==='/bench/stats') return json(res,200,{...counters,intents:intents.size,captured:[...intents.values()].filter(x=>x.status==='succeeded').length,failedDeliveries:[...deliveries.values()].filter(x=>!x.done).length,latencyMs:timings,eventLoopP99Ms:delay.percentile(99)/1e6,memory:process.memoryUsage()});
    let body='';for await(const chunk of req){body+=chunk;if(body.length>262144)throw Error('body too large');}
    const form=new URLSearchParams(body);
    if(url.pathname==='/v1/payment_intents'&&req.method==='POST') {
      await sleep(timings.create);const key=req.headers['idempotency-key'];
      if(key&&keys.has(key)){counters.createReplay++;return json(res,200,intents.get(keys.get(key)));}
      const id=`pi_${randomUUID().replaceAll('-','')}`,metadata={};
      for(const [k,v]of form) if(k.startsWith('metadata['))metadata[k.slice(9,-1)]=v;
      const intent={id,object:'payment_intent',amount:Number(form.get('amount')),amount_received:0,currency:'vnd',status:'requires_payment_method',client_secret:`${id}_secret_bench`,metadata,livemode:false,latest_charge:null,created:Math.floor(Date.now()/1000),payment_method_types:['card'],confirmation_method:'automatic',capture_method:'automatic'};
      intents.set(id,intent);if(key)keys.set(key,id);counters.create++;return json(res,200,intent);
    }
    if(url.pathname==='/v1/payment_intents/search') {const query=url.searchParams.get('query')??'';return json(res,200,{object:'search_result',data:[...intents.values()].filter(x=>query.includes(x.metadata.payment_transaction_id)),has_more:false,url:url.pathname});}
    const match=url.pathname.match(/^\/v1\/payment_intents\/(pi_[a-zA-Z0-9]+)(?:\/(confirm|cancel))?$/);
    if(match){const intent=intents.get(match[1]);if(!intent)return json(res,404,{error:{message:'missing intent',type:'invalid_request_error',code:'resource_missing'}});
      if(match[2]==='confirm'&&req.method==='POST') {await sleep(timings.confirm);if(intent.status!=='succeeded'){intent.status='succeeded';intent.amount_received=intent.amount;intent.latest_charge=`ch_${intent.id.slice(3)}`;intent.bench_captured_at_ms=Date.now();counters.confirm++;}json(res,200,intent);void webhook(intent);return;}
      if(match[2]==='cancel'&&req.method==='POST'){intent.status=intent.status==='succeeded'?'succeeded':'canceled';return json(res,200,intent);}
      counters.retrieve++;await sleep(timings.retrieve);return json(res,200,intent);
    }
    return json(res,404,{error:{message:'unsupported simulator route',type:'invalid_request_error'}});
  }catch(error){json(res,500,{error:{message:error.message,type:'api_error'}});}
}).listen(8080,'0.0.0.0',()=>console.log('Isolated Stripe HTTP simulator and fixture JWKS ready'));
