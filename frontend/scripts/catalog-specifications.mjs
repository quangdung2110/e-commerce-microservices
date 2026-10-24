/** Explicit local seed schema. Unknown configurations fail before any catalog write. */
export const specificationNames = {
  'dung-luong-luu-tru':'Dung lượng lưu trữ', ram:'RAM', 'o-cung':'Ổ cứng', 'ket-noi':'Kết nối',
  'tinh-nang-am-thanh':'Tính năng âm thanh', 'kieu-hop-sac':'Kiểu hộp sạc', 'cong-suat':'Công suất',
  'kich-thuoc-mat':'Kích thước mặt', 'chat-lieu-day':'Chất liệu dây', 'dung-luong-pin':'Dung lượng pin',
  'so-cong':'Số cổng sạc', 'so-chuc-nang':'Số chức năng hub', 'loai-switch':'Loại switch',
  'do-phan-giai':'Độ phân giải', 'anh-sang':'Ánh sáng', 'tram-sac':'Trạm sạc',
};
export const seedProducts = {
  'dien-thoai':['dien-thoai-terra-x1','dien-thoai-terra-x1-pro','dien-thoai-orion-s12','dien-thoai-luma-mini','dien-thoai-terra-fold'],
  laptop:['laptop-nova-air-13','laptop-nova-studio-14','laptop-orion-book-15','laptop-vortex-gaming-16'],
  'am-thanh':['tai-nghe-terra-buds','tai-nghe-luma-air','tai-nghe-orion-studio','loa-bluetooth-terra-beat'],
  'may-tinh-bang':['may-tinh-bang-nova-pad-11','may-tinh-bang-luma-tab-mini','may-tinh-bang-orion-pad-pro'],
  'dong-ho-thong-minh':['dong-ho-terra-watch-fit','dong-ho-nova-watch-pro','vong-deo-luma-band'],
  'phu-kien':['sac-du-phong-terra-power','cu-sac-nova-gan','hub-usb-c-orion-connect'],
  gaming:['ban-phim-co-vortex-k75','chuot-gaming-orion-g5','tay-cam-terra-play'],
  'nha-thong-minh':['camera-luma-home','den-thong-minh-nova-light','robot-hut-bui-terra-clean'],
};
export function specificationsFor(slug, configuration) {
  let match;
  if (slug.startsWith('dien-thoai-') && /^(\d+)(GB|TB)$/i.test(configuration)) return {'dung-luong-luu-tru':configuration};
  if (slug.startsWith('laptop-') && (match=/^(\d+GB) \/ (\d+(?:GB|TB))$/i.exec(configuration))) return {ram:match[1],'o-cung':match[2]};
  if (slug.startsWith('may-tinh-bang-') && (match=/^(?:(Wi-Fi|5G) \/ )?(\d+(?:GB|TB))$/i.exec(configuration))) return {'dung-luong-luu-tru':match[2],...(match[1]?{'ket-noi':match[1]}:{})};
  if (['tai-nghe-terra-buds','tai-nghe-luma-air'].includes(slug) && ['Tiêu chuẩn','Sạc không dây'].includes(configuration)) return {'kieu-hop-sac':configuration};
  if (slug==='tai-nghe-orion-studio' && ['Bluetooth','Bluetooth + ANC'].includes(configuration)) return {'ket-noi':'Bluetooth','tinh-nang-am-thanh':configuration.includes('ANC')?'ANC':'Tiêu chuẩn'};
  if (slug==='loa-bluetooth-terra-beat' && /^\d+W$/i.test(configuration)) return {'cong-suat':configuration};
  if (slug.startsWith('dong-ho-') && (match=/^(\d+mm)(?: (GPS|LTE))?$/.exec(configuration))) return {'kich-thuoc-mat':match[1],...(match[2]?{'ket-noi':match[2]}:{})};
  if (slug==='vong-deo-luma-band' && ['Dây silicone','Dây vải'].includes(configuration)) return {'chat-lieu-day':configuration.slice(4)};
  if (slug==='sac-du-phong-terra-power' && (match=/^(\d+mAh) \/ (\d+W)$/.exec(configuration))) return {'dung-luong-pin':match[1],'cong-suat':match[2]};
  if (slug==='cu-sac-nova-gan' && (match=/^(\d+W) \/ (\d+) cổng$/.exec(configuration))) return {'cong-suat':match[1],'so-cong':match[2]};
  if (slug==='hub-usb-c-orion-connect' && (match=/^(\d+) trong 1$/.exec(configuration))) return {'so-chuc-nang':match[1]};
  if (slug==='ban-phim-co-vortex-k75' && /^(Red|Brown|Blue) switch$/i.test(configuration)) return {'loai-switch':configuration};
  if (slug==='chuot-gaming-orion-g5' && ['Có dây','Không dây','Không dây Pro'].includes(configuration)) return {'ket-noi':configuration};
  if (slug==='tay-cam-terra-play' && ['USB-C','Bluetooth','Bluetooth + dock'].includes(configuration)) return {'ket-noi':configuration};
  if (slug==='camera-luma-home' && (match=/^(2K|4K) \/ (\d+GB)$/.exec(configuration))) return {'do-phan-giai':match[1],'dung-luong-luu-tru':match[2]};
  if (slug==='den-thong-minh-nova-light' && (match=/^(\d+W) \/ (RGB|ánh sáng trắng)$/.exec(configuration))) return {'cong-suat':match[1],'anh-sang':match[2]};
  if (slug==='robot-hut-bui-terra-clean' && ['Tiêu chuẩn','Có trạm sạc','Trạm tự đổ bụi'].includes(configuration)) return {'tram-sac':configuration};
  throw new Error(`Unmapped seed specification: ${slug} / ${configuration}`);
}

export function structuredVariant(slug, attributes) {
  if (!Object.hasOwn(attributes,'cau-hinh')) return {...attributes,...(slug==='tai-nghe-orion-studio'&&attributes['ket-noi']==='Bluetooth'&&!attributes['tinh-nang-am-thanh']?{'tinh-nang-am-thanh':'Tiêu chuẩn'}:{})};
  const {'cau-hinh':configuration,...rest}=attributes;
  return {...rest,...specificationsFor(slug,configuration)};
}
