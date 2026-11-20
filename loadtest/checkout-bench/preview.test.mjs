import test from 'node:test';
import assert from 'node:assert/strict';
import {recoverPreview} from './preview.mjs';
test('truncated Preview response recovers without a new logical request',async()=>{
  let attempts=0;
  const result=await recoverPreview({request:async()=>++attempts===1?{status:200,data:null}:{status:200,data:{checkout_token:'same-logical-quote'}},pause:async()=>{}});
  assert.equal(attempts,2);assert.equal(result.data.checkout_token,'same-logical-quote');
});
test('unreadable Preview responses stay failures after bounded retries',async()=>{
  let attempts=0;
  await assert.rejects(recoverPreview({request:async()=>{attempts++;return {status:200,data:null};},pause:async()=>{}}),/preview_invalid_response/);
  assert.equal(attempts,3);
});
test('Preview business errors are terminal',async()=>{
  let attempts=0;
  await assert.rejects(recoverPreview({request:async()=>{attempts++;return {status:409,data:{code:'CART_CHANGED'}};},pause:async()=>{throw Error('must not retry');}}),/preview_409_CART_CHANGED/);
  assert.equal(attempts,1);
});
