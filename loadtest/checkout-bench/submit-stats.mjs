import {distribution} from './stats.mjs';
// Sessions occupy [Submit, client-visible PAID); replacements at the same millisecond do not overlap.
export function submitConcurrency(rows,from,to,target){
  const events=new Map();
  for(const row of rows){
    const start=row.submitAt??row.startedAt,end=row.paidAt??row.finishedAt;
    if(end<=start)continue;
    events.set(start,(events.get(start)??0)+1);events.set(end,(events.get(end)??0)-1);
  }
  const ordered=[...events].sort((a,b)=>a[0]-b[0]);
  let active=0,peak=0,area=0,atTarget=0,last=ordered[0]?.[0]??from;
  for(const [time,delta] of ordered){
    const length=Math.max(0,Math.min(time,to)-Math.max(last,from));
    area+=active*length;if(active>=target)atTarget+=length;
    active+=delta;peak=Math.max(peak,active);last=time;
  }
  const span=Math.max(0,to-from);
  return {peak,target,windowStart:from,windowEnd:to,windowMs:span,timeWeightedMean:span?area/span:0,fractionAtTarget:span?atTarget/span:0};
}
export function submitChecks(rows,requests){
  const errors=requests.filter(r=>r.status===0||r.status>=400||r.error);
  const failures=rows.filter(r=>r.outcome!=='paid');
  const sample={sessions:rows.length,paid:rows.length-failures.length,failedSessions:failures.length,httpErrors:errors.length,networkErrors:errors.filter(r=>r.status===0).length,http429:errors.filter(r=>r.status===429).length,http5xx:errors.filter(r=>r.status>=500).length,strictZeroErrors:rows.length>0&&failures.length===0&&errors.length===0,submitToPaidMs:distribution(rows.filter(r=>r.paidAt).map(r=>r.paidAt-r.submitAt)),allSessionDurationMs:distribution(rows.map(r=>(r.paidAt??r.finishedAt)-r.submitAt))};
  return sample;
}
