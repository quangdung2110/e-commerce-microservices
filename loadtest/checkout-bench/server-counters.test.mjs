import test from 'node:test';
import assert from 'node:assert/strict';
import {serverErrorDelta} from './server-counters.mjs';
const sample=n=>({values:Array.from({length:6},(_,i)=>({service:`service-${i}`,status:200,counters:`http_server_requests_seconds_count{status="503",uri="/test"} ${n}\nhttp_server_requests_seconds_count{status="200",uri="/test"} 999`}))});
test('server error comparison uses deltas, not historical startup errors',()=>{assert.equal(serverErrorDelta(sample(3),sample(3)).javaHttp5xx,0);assert.equal(serverErrorDelta(sample(3),sample(4)).javaHttp5xx,6);});
test('missing service metrics invalidate internal error evidence',()=>{const after=sample(1);after.values[0].status=503;assert.equal(serverErrorDelta(sample(1),after).valid,false);});
