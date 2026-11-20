// A truncated HTTP 200 is ambiguous. Reuse the Preview key, just as for a timeout.
export async function recoverPreview({request,pause,expired=()=>false}) {
  let response;
  for(let attempt=0;attempt<3&&!expired();attempt++) {
    response=await request();
    if(response.status===200&&typeof response.data?.checkout_token==='string'&&response.data.checkout_token) return response;
    if(response.status!==200&&![0,429,503].includes(response.status)) break;
    if(attempt<2&&!expired()) await pause(response.retryAfter);
  }
  if(response?.status===200) throw Error('preview_invalid_response');
  throw Error(`preview_${response?.status??0}_${response?.data?.code}`);
}
