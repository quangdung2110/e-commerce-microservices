import {dc} from './prepare.mjs';
export function serverCounters(){
 return JSON.parse(dc(['exec','-T','provider','node','-e',`Promise.all([['checkout-service',8080],['order-service',8080],['inventory-service',8080],['payment-service',8080],['promotion-service',8085],['product-service',8081]].map(async([service,port])=>{try{const r=await fetch('http://'+service+':'+port+'/actuator/prometheus',{signal:AbortSignal.timeout(4000)});const text=await r.text();return {service,status:r.status,counters:text.split('\\n').filter(l=>l.startsWith('http_server_requests_seconds_count')).join('\\n')}}catch(e){return {service,error:e.name}}})).then(x=>console.log(JSON.stringify({at:new Date().toISOString(),values:x})))`]));
}
export function serverErrorDelta(before,after){
 const read=snapshot=>new Map(snapshot.values.flatMap(s=>(s.counters??'').split('\n').filter(l=>/status="5\d\d"/.test(l)).map(l=>[s.service+':'+l.slice(0,l.lastIndexOf(' ')),Number(l.slice(l.lastIndexOf(' ')+1))])));
 const a=read(before),b=read(after),changes=[];
 for(const [key,n]of b){const delta=n-(a.get(key)??0);if(delta>0)changes.push({metric:key,delta});}
 const valid=before.values.length===6&&after.values.length===6&&[...before.values,...after.values].every(s=>s.status===200);
 return {valid,javaHttp5xx:changes.reduce((n,c)=>n+c.delta,0),changes};
}
