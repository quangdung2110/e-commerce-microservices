import test from 'node:test';
import assert from 'node:assert/strict';
import {submitConcurrency,submitChecks} from './submit-stats.mjs';
test('a replacement starting exactly at PAID does not inflate concurrent sessions',()=>{
 const r=submitConcurrency([{submitAt:0,paidAt:10},{submitAt:10,paidAt:20}],0,20,1);
 assert.equal(r.peak,1);assert.equal(r.fractionAtTarget,1);assert.equal(r.timeWeightedMean,1);
});
test('recovered 503 is still an error in the strict zero-error gate',()=>{
 const r=submitChecks([{submitAt:0,paidAt:5,outcome:'paid'}],[{status:503},{status:200}]);
 assert.equal(r.paid,1);assert.equal(r.strictZeroErrors,false);assert.equal(r.http5xx,1);
});
test('202 pending is normal protocol; malformed 200 is an error',()=>{
 const rows=[{submitAt:0,paidAt:5,outcome:'paid'}];
 assert.equal(submitChecks(rows,[{status:202},{status:200}]).strictZeroErrors,true);
 assert.equal(submitChecks(rows,[{status:200,error:'INVALID_JSON'}]).strictZeroErrors,false);
});
test('timeouts remain in both denominator and all-session durations',()=>{
 const r=submitChecks([{submitAt:0,paidAt:5,outcome:'paid'},{submitAt:0,finishedAt:180000,outcome:'paid_timeout'}],[{status:0}]);
 assert.equal(r.sessions,2);assert.equal(r.failedSessions,1);assert.equal(r.allSessionDurationMs.p99,180000);assert.equal(r.strictZeroErrors,false);
});
