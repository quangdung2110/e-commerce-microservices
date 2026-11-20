// @vitest-environment jsdom
import {act,cleanup,renderHook} from '@testing-library/react';
import {afterEach,beforeEach,expect,it,vi} from 'vitest';
vi.mock('@/lib/api/review',()=>({reviewApi:{issueUpload:vi.fn()}}));
vi.mock('@/lib/review-media',async original=>({...await original<typeof import('@/lib/review-media')>(),uploadToStorage:vi.fn()}));
import {useReviewMediaUploader} from '@/hooks/use-review-media-uploader';
import {reviewApi} from '@/lib/api/review';
import {uploadToStorage} from '@/lib/review-media';
import {useAuthStore} from '@/store/useAuthStore';
afterEach(()=>{cleanup();vi.unstubAllGlobals();});
beforeEach(()=>{vi.clearAllMocks();vi.unstubAllGlobals();URL.createObjectURL=vi.fn(()=> 'blob:QA');URL.revokeObjectURL=vi.fn();});
it('REV16 must not start storage PUT when signed-instruction response arrives after unmount',async()=>{
 let resolve!: (value:Awaited<ReturnType<typeof reviewApi.issueUpload>>)=>void;
 vi.mocked(reviewApi.issueUpload).mockReturnValue(new Promise(r=>{resolve=r;}));
 vi.mocked(uploadToStorage).mockResolvedValue(undefined);
 const {result,unmount}=renderHook(()=>useReviewMediaUploader());
 act(()=>{result.current.add([new File(['QA'],'qa.png',{type:'image/png'})]);});
 expect(reviewApi.issueUpload).toHaveBeenCalledTimes(1);
 unmount();
 await act(async()=>{resolve({asset_id:'qa-asset',expires_at:new Date(Date.now()+900000).toISOString(),upload:{method:'PUT',url:'https://storage.test/qa',headers:{'Content-Type':'image/png'}}});await Promise.resolve();});
 expect(uploadToStorage).not.toHaveBeenCalled();
});
it.each(['remove','reset','account switch'] as const)('does not start a late PUT after %s',async(action)=>{
 let resolve!: (value:Awaited<ReturnType<typeof reviewApi.issueUpload>>)=>void;
 vi.mocked(reviewApi.issueUpload).mockReturnValue(new Promise(r=>{resolve=r;}));
 const {result}=renderHook(()=>useReviewMediaUploader());
 act(()=>{result.current.add([new File(['QA'],'qa.png',{type:'image/png'})]);});
 const signal=vi.mocked(reviewApi.issueUpload).mock.calls[0][1]!;
 act(()=>{
   if(action==='remove')result.current.remove(result.current.items[0].key);
   else if(action==='reset')result.current.reset();
   else useAuthStore.getState().setSession(`e30.${btoa(JSON.stringify({sub:'other-buyer',roles:['USER']}))}.test`);
 });
 expect(signal.aborted).toBe(true);
 await act(async()=>{resolve({asset_id:'qa-asset',expires_at:new Date(Date.now()+900000).toISOString(),upload:{method:'PUT',url:'https://storage.test/qa',headers:{}}});});
 expect(uploadToStorage).not.toHaveBeenCalled();
 expect(result.current.items).toHaveLength(0);
});
it('REV16 storage helper must not send when the supplied signal was already aborted',async()=>{
 const actual=await vi.importActual<typeof import('@/lib/review-media')>('@/lib/review-media');
 const send=vi.fn();
 class Xhr {upload={};open=vi.fn();setRequestHeader=vi.fn();abort=vi.fn();send=send;}
 vi.stubGlobal('XMLHttpRequest',Xhr);
 const controller=new AbortController();controller.abort();
 await expect(actual.uploadToStorage({method:'PUT',url:'https://storage.test/qa',headers:{}},new Blob(['QA']),{signal:controller.signal})).rejects.toMatchObject({name:'AbortError'});
 expect(send).not.toHaveBeenCalled();
});
