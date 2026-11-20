import {test} from 'node:test';
import assert from 'node:assert/strict';
import {structuredVariant} from './catalog-specifications.mjs';
test('splits laptop configuration while preserving all other attributes',()=>{
  assert.deepEqual(structuredVariant('laptop-nova-air-13',{'mau-sac':'Bạc','cau-hinh':'16GB / 1TB'}),{'mau-sac':'Bạc',ram:'16GB','o-cung':'1TB'});
});
test('separates camera and charger specifications and is idempotent',()=>{
  const camera=structuredVariant('camera-luma-home',{'cau-hinh':'2K / 32GB','mau-sac':'Trắng'});
  assert.deepEqual(camera,{'mau-sac':'Trắng','do-phan-giai':'2K','dung-luong-luu-tru':'32GB'});
  assert.deepEqual(structuredVariant('camera-luma-home',camera),camera);
  assert.deepEqual(structuredVariant('cu-sac-nova-gan',{'cau-hinh':'65W / 2 cổng'}),{'cong-suat':'65W','so-cong':'2'});
});
test('refuses unknown seed metadata instead of silently inventing specifications',()=>{
  assert.throws(()=>structuredVariant('laptop-nova-air-13',{'cau-hinh':'unspecified'}),/Unmapped seed/);
});
