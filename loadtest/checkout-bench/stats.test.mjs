import test from 'node:test';
import assert from 'node:assert/strict';
import { percentile,concurrency,summarize,evaluateSlo,distribution } from './stats.mjs';
test('nearest rank keeps the slow tail',()=>{assert.equal(percentile([5,1,9,3],.99),9);assert.equal(percentile([],.5),null);});
test('journey concurrency ends at PAID while convergence continues',()=>{const s=concurrency([{startedAt:0,paidAt:10,finishedAt:100},{startedAt:5,finishedAt:20}]);assert.equal(s.peak,2);assert.equal(s.activeMilliseconds,25);assert.equal(s.timeWeightedMean,1.25);});
test('failed sessions remain in denominator and full-cohort durations',()=>{const s=summarize([{scheduledAt:0,startedAt:0,previewAt:1,submitAt:1,readyAt:2,captureAt:3,paidAt:4,finishedAt:5,thinkMs:0,outcome:'paid'},{scheduledAt:0,startedAt:0,finishedAt:180000,outcome:'paid_timeout'}],[]);assert.equal(s.successRate,.5);assert.equal(s.journeyMs.p99,180000);assert.equal(s.paid,1);});
test('fast failures cannot pass the completion gate',()=>{const s=summarize([{scheduledAt:0,startedAt:0,finishedAt:2,outcome:'preview_503'}],[]);assert.equal(evaluateSlo(s).observedLatencyAndSuccessPass,false);});
test('large request samples avoid argument-stack limits',()=>{const d=distribution(Array.from({length:200000},(_,i)=>i));assert.equal(d.max,199999);assert.equal(d.p99,197999);});
test('unknown provider capture time cannot silently pass the latency gate',()=>{const s=summarize([{scheduledAt:0,startedAt:0,previewAt:1,submitAt:1,readyAt:2,paidAt:4,finishedAt:5,convergedAt:5,thinkMs:0,outcome:'paid'}],[]);assert.equal(s.paid,1);assert.equal(s.captureTimingUnknown,1);assert.equal(s.captureVisibleMs.count,0);assert.equal(s.captureVisibleMs.p99,null);assert.equal(evaluateSlo(s).checks.captureTimingCoverage,false);});
