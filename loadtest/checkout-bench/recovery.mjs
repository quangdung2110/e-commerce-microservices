/** Mirror the public Checkout contract: query ambiguous outcomes; replay only confirmed absence. */
export async function recoverSubmission(initial,{read,replay,pause,expired}) {
  let current=initial;
  while(!current.data?.order_id&&!expired()){
    const absent=current.status===404&&current.data?.code==='SUBMISSION_NOT_FOUND';
    if(!absent&&![0,202,429,503].includes(current.status))throw Error(`submit_${current.status}_${current.data?.code}`);
    await pause(current.retryAfter);
    current=absent?await replay():await read();
  }
  return current;
}
