import test from 'node:test';
import assert from 'node:assert/strict';
import { recoverSubmission } from './recovery.mjs';
test('ambiguous Submit is queried before same-token replay after confirmed absence',async()=>{const calls=[];const token='immutable-checkout-token';const result=await recoverSubmission({status:503},{expired:()=>false,pause:async()=>{},read:async()=>{calls.push('GET');return{status:404,data:{code:'SUBMISSION_NOT_FOUND'}};},replay:async()=>{calls.push(`POST:${token}`);return{status:201,data:{order_id:'1'}};}});assert.deepEqual(calls,['GET',`POST:${token}`]);assert.equal(result.data.order_id,'1');});
test('accepted pending work is polled without another Submit',async()=>{let reads=0;await recoverSubmission({status:202},{expired:()=>false,pause:async()=>{},read:async()=>++reads===1?{status:202}:{status:200,data:{order_id:'2'}},replay:async()=>{assert.fail('duplicate submit');}});assert.equal(reads,2);});
test('business terminal problem is preserved',async()=>{await assert.rejects(recoverSubmission({status:409,data:{code:'SKU_UNAVAILABLE'}},{expired:()=>false,pause:async()=>{},read:async()=>assert.fail(),replay:async()=>assert.fail()}),/SKU_UNAVAILABLE/);});
