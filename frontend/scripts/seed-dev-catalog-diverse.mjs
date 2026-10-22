/**
 * Add a varied local catalog through Product's admin API (never write Elasticsearch).
 * Run: node frontend/scripts/seed-dev-catalog-diverse.mjs
 * If local Kafka Connect is absent, add --replay-outbox to replay the latest
 * canonical seed events from PostgreSQL into Kafka using the local Docker CLI.
 * DOCKER_BIN can override the CLI location. No outbox row is changed or deleted.
 * Uses the same dev account as seed-dev-catalog.sh; ADMIN_EMAIL/ADMIN_PASSWORD override it.
 * Reuses categories/attributes/products and missing SKU codes on subsequent runs.
 * Stock remains unknown: Inventory is the only owner of availability.
 */
import { spawnSync } from 'node:child_process';
import path from 'node:path';
const gateway = process.env.GATEWAY ?? 'http://localhost:8088';
if (!['localhost', '127.0.0.1', '[::1]'].includes(new URL(gateway).hostname)) {
  throw new Error('This development seed only accepts a local gateway.');
}
let token;
async function request(path, { method = 'GET', body, etag } = {}) {
  const response = await fetch(`${gateway}${path}`, {
    method, signal: AbortSignal.timeout(15000),
    headers: { 'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(etag ? { 'If-Match': etag } : {}) },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
  const data = await response.json().catch(() => null);
  if (!response.ok) throw new Error(`${method} ${path}: HTTP ${response.status} ${data?.code ?? ''} ${data?.detail ?? ''}`);
  return { data, etag: response.headers.get('etag') };
}

const categories = [
  ['dien-thoai', 'Điện thoại'], ['laptop', 'Laptop'], ['am-thanh', 'Âm thanh'],
  ['may-tinh-bang', 'Máy tính bảng'], ['dong-ho-thong-minh', 'Đồng hồ thông minh'],
  ['phu-kien', 'Phụ kiện'], ['gaming', 'Gaming'], ['nha-thong-minh', 'Nhà thông minh'],
];
// [category slug, product slug, name, brand, base price, configurations, colors, description]
const products = [
  ['dien-thoai','dien-thoai-orion-s12','Điện thoại Orion S12','Orion',8990000,['128GB','256GB','512GB'],['Đen','Xanh','Bạc'],'Màn hình AMOLED 6,4 inch, camera 50MP, pin 5000mAh.'],
  ['dien-thoai','dien-thoai-luma-mini','Điện thoại Luma Mini','Luma',5990000,['128GB','128GB','256GB'],['Trắng','Hồng','Xanh'],'Thiết kế nhỏ gọn, màn hình 6,1 inch và sạc nhanh 33W.'],
  ['dien-thoai','dien-thoai-terra-fold','Điện thoại Terra Fold','Terra',25990000,['256GB','512GB','512GB'],['Đen','Bạc','Xanh'],'Điện thoại màn hình gập, hỗ trợ đa nhiệm và chụp ảnh góc rộng.'],
  ['laptop','laptop-nova-studio-14','Laptop Nova Studio 14','Nova',21990000,['16GB / 512GB','16GB / 1TB','32GB / 1TB'],['Bạc','Đen','Đen'],'Laptop 14 inch dành cho thiết kế và làm việc, màn hình độ phân giải cao.'],
  ['laptop','laptop-orion-book-15','Laptop Orion Book 15','Orion',14990000,['8GB / 256GB','16GB / 512GB','16GB / 1TB'],['Bạc','Xanh','Đen'],'Laptop học tập và văn phòng, bàn phím đầy đủ và màn hình 15,6 inch.'],
  ['laptop','laptop-vortex-gaming-16','Laptop Vortex Gaming 16','Vortex',28990000,['16GB / 512GB','16GB / 1TB','32GB / 1TB'],['Đen','Đen','Xám'],'Laptop gaming màn hình 165Hz, tản nhiệt kép và bàn phím RGB.'],
  ['am-thanh','tai-nghe-luma-air','Tai nghe không dây Luma Air','Luma',1290000,['Tiêu chuẩn','Tiêu chuẩn','Sạc không dây'],['Trắng','Đen','Hồng'],'Tai nghe Bluetooth nhỏ gọn, micro kép và hộp sạc USB-C.'],
  ['am-thanh','tai-nghe-orion-studio','Tai nghe chụp tai Orion Studio','Orion',3490000,['Bluetooth','Bluetooth','Bluetooth + ANC'],['Đen','Kem','Bạc'],'Tai nghe chụp tai đệm mềm, thời lượng pin dài và chế độ chống ồn.'],
  ['am-thanh','loa-bluetooth-terra-beat','Loa Bluetooth Terra Beat','Terra',1890000,['20W','20W','30W'],['Đen','Xanh','Cam'],'Loa di động chống nước, hỗ trợ kết nối stereo và sạc USB-C.'],
  ['may-tinh-bang','may-tinh-bang-nova-pad-11','Máy tính bảng Nova Pad 11','Nova',7990000,['Wi-Fi / 128GB','Wi-Fi / 256GB','5G / 256GB'],['Bạc','Xám','Xám'],'Máy tính bảng 11 inch, bốn loa và hỗ trợ bút cảm ứng.'],
  ['may-tinh-bang','may-tinh-bang-luma-tab-mini','Máy tính bảng Luma Tab Mini','Luma',4490000,['64GB','128GB','128GB'],['Trắng','Xanh','Hồng'],'Máy tính bảng 8,4 inch nhỏ gọn để đọc sách, học tập và giải trí.'],
  ['may-tinh-bang','may-tinh-bang-orion-pad-pro','Máy tính bảng Orion Pad Pro','Orion',13990000,['Wi-Fi / 256GB','Wi-Fi / 512GB','5G / 512GB'],['Bạc','Đen','Đen'],'Màn hình 12,4 inch 120Hz, hỗ trợ bàn phím và đa nhiệm.'],
  ['dong-ho-thong-minh','dong-ho-terra-watch-fit','Đồng hồ thông minh Terra Watch Fit','Terra',1490000,['40mm','40mm','44mm'],['Đen','Hồng','Xanh'],'Theo dõi vận động, nhịp tim và giấc ngủ; pin dùng nhiều ngày.'],
  ['dong-ho-thong-minh','dong-ho-nova-watch-pro','Đồng hồ thông minh Nova Watch Pro','Nova',4990000,['42mm GPS','46mm GPS','46mm LTE'],['Bạc','Đen','Đen'],'Đồng hồ GPS, màn hình AMOLED và hỗ trợ nghe gọi Bluetooth.'],
  ['dong-ho-thong-minh','vong-deo-luma-band','Vòng đeo thông minh Luma Band','Luma',790000,['Dây silicone','Dây silicone','Dây vải'],['Đen','Tím','Xanh'],'Vòng đeo nhẹ, nhiều chế độ thể thao và thông báo điện thoại.'],
  ['phu-kien','sac-du-phong-terra-power','Sạc dự phòng Terra Power','Terra',490000,['10000mAh / 20W','20000mAh / 30W','20000mAh / 65W'],['Trắng','Đen','Xám'],'Sạc dự phòng USB-C, màn hình hiển thị pin và bảo vệ quá nhiệt.'],
  ['phu-kien','cu-sac-nova-gan','Củ sạc nhanh Nova GaN','Nova',390000,['30W / 1 cổng','65W / 2 cổng','100W / 3 cổng'],['Trắng','Đen','Đen'],'Củ sạc GaN nhỏ gọn cho điện thoại, máy tính bảng và laptop.'],
  ['phu-kien','hub-usb-c-orion-connect','Hub USB-C Orion Connect','Orion',690000,['5 trong 1','7 trong 1','9 trong 1'],['Bạc','Xám','Đen'],'Hub mở rộng USB-C với HDMI, USB-A và cổng sạc PD.'],
  ['gaming','ban-phim-co-vortex-k75','Bàn phím cơ Vortex K75','Vortex',1490000,['Red switch','Brown switch','Blue switch'],['Đen','Trắng','Xám'],'Bàn phím cơ 75%, đèn RGB, thay switch và kết nối USB-C.'],
  ['gaming','chuot-gaming-orion-g5','Chuột gaming Orion G5','Orion',590000,['Có dây','Không dây','Không dây Pro'],['Đen','Trắng','Đen'],'Chuột gaming cảm biến chính xác, nút lập trình và thiết kế nhẹ.'],
  ['gaming','tay-cam-terra-play','Tay cầm chơi game Terra Play','Terra',890000,['USB-C','Bluetooth','Bluetooth + dock'],['Đen','Trắng','Xanh'],'Tay cầm hỗ trợ PC và thiết bị di động, rung phản hồi và cò analog.'],
  ['nha-thong-minh','camera-luma-home','Camera Wi-Fi Luma Home','Luma',690000,['2K / 32GB','2K / 64GB','4K / 128GB'],['Trắng','Trắng','Đen'],'Camera trong nhà xoay 360 độ, đàm thoại hai chiều và nhìn đêm.'],
  ['nha-thong-minh','den-thong-minh-nova-light','Đèn thông minh Nova Light','Nova',290000,['9W / ánh sáng trắng','9W / RGB','12W / RGB'],['Trắng','Trắng','Trắng'],'Đèn Wi-Fi điều chỉnh độ sáng, hẹn giờ và chọn màu qua ứng dụng.'],
  ['nha-thong-minh','robot-hut-bui-terra-clean','Robot hút bụi Terra Clean','Terra',5990000,['Tiêu chuẩn','Có trạm sạc','Trạm tự đổ bụi'],['Trắng','Đen','Trắng'],'Robot hút bụi và lau sàn, lập bản đồ căn phòng và lịch vệ sinh.'],
];
const existing = [
  ['dien-thoai','dien-thoai-terra-x1','Điện thoại Terra X1 128GB','Terra',12990000,['128GB','128GB','256GB'],['Đen','Xanh','Bạc'],'Điện thoại Terra X1 với nhiều màu và dung lượng lưu trữ.'],
  ['dien-thoai','dien-thoai-terra-x1-pro','Điện thoại Terra X1 Pro','Terra',18490000,['256GB','256GB','512GB'],['Đen','Bạc','Xanh'],'Điện thoại Terra X1 Pro cho chụp ảnh và đa nhiệm.'],
  ['laptop','laptop-nova-air-13','Laptop Nova Air 13','Nova',24990000,['16GB / 512GB','16GB / 1TB','32GB / 1TB'],['Bạc','Xám','Đen'],'Laptop Nova Air 13 mỏng nhẹ cho công việc và di chuyển.'],
  ['am-thanh','tai-nghe-terra-buds','Tai nghe không dây Terra Buds','Terra',1990000,['Tiêu chuẩn','Tiêu chuẩn','Sạc không dây'],['Trắng','Đen','Xanh'],'Tai nghe Bluetooth Terra Buds với hộp sạc và nhiều màu lựa chọn.'],
];
const attributeSpecs = [['thuong-hieu','Thương hiệu',false],['mau-sac','Màu sắc',true],['cau-hinh','Cấu hình',true]];
const stats = { categories_created: 0, products_created: 0, skus_added: 0 };

async function replayOutbox(categoryMap) {
  const docker = process.env.DOCKER_BIN ?? (process.platform === 'win32'
    ? path.join(process.env.LOCALAPPDATA, 'Programs', 'DockerDesktop', 'resources', 'bin', 'docker.exe') : 'docker');
  function run(args, input) {
    const result = spawnSync(docker,args,{input,encoding:'utf8',timeout:60000,maxBuffer:8*1024*1024});
    if (result.error || result.status !== 0) throw new Error(`Local outbox replay failed: ${result.error?.message ?? result.stderr}`);
    return result.stdout;
  }
  const quoted = values => values.map(value => `'${value.replaceAll("'","''")}'`).join(',');
  const groups = [
    {topic:'t.category.events',type:'category',id:'category_id',slugs:categories.map(c=>c[0]),ids:categories.map(([slug])=>categoryMap.get(slug).category_id)},
    {topic:'t.product.events',type:'sku',id:'sku_id',slugs:[...existing,...products].map(p=>p[1])},
  ];
  for (const group of groups) {
    const slugPath = group.type === 'category' ? 'category' : 'product';
    const sql = `SELECT partition_key || chr(9) || payload::text FROM (
      SELECT DISTINCT ON (payload->'data'->'${group.type}'->>'${group.id}') partition_key,payload,created_at
      FROM product_outbox_events
      WHERE event_type='com.ecommerce.catalog.${group.type}-upserted.v1'
        AND payload->'data'->'${slugPath}'->>'slug' IN (${quoted(group.slugs)})
        ${group.ids ? `AND payload->'data'->'category'->>'category_id' IN (${quoted(group.ids)})` : ''}
      ORDER BY payload->'data'->'${group.type}'->>'${group.id}', (payload->>'sequence')::bigint DESC,created_at DESC
    ) latest ORDER BY created_at;`;
    const records = run(['exec','postgres','psql','-U','postgres','-d','product_service','-v','ON_ERROR_STOP=1','-At','-c',sql]).trim();
    if (!records) throw new Error(`No seed outbox events found for ${group.topic}`);
    run(['exec','-i','kafka','kafka-console-producer','--bootstrap-server','kafka:9092','--producer.config','/etc/kafka/admin.properties','--topic',group.topic,'--property','parse.key=true'],records+'\n');
    console.log(`→ Replayed ${records.split('\n').length} canonical events to ${group.topic}`);
    // Let categories become visible before SKU events perform category enrichment.
    if (group.type === 'category') {
      for (let attempt=0;attempt<20;attempt++) {
        const tree=(await request('/api/v2/search/categories')).data.categories;
        if (categories.every(([slug])=>tree.some(c=>c.slug===slug))) break;
        await new Promise(resolve=>setTimeout(resolve,500));
      }
    }
  }
  for (let attempt=0;attempt<30;attempt++) {
    const page=(await request('/api/v2/search/products?in_stock=false&size=100')).data;
    const seeded=page.items.filter(item=>[...existing,...products].some(p=>p[1]===item.product_slug));
    if (seeded.length===28) {
      if (seeded.some(item=>item.is_in_stock)) throw new Error('Unexpected confirmed stock in the seeded catalog.');
      console.log('✓ Search verified: 28 product cards, all stock unconfirmed.');
      return;
    }
    await new Promise(resolve=>setTimeout(resolve,1000));
  }
  throw new Error('Product seed succeeded but Search has not grouped all 28 products. Inspect Search worker logs.');
}

async function main() {
  token = (await request('/api/v1/auth/sessions', { method: 'POST', body: {
    email: process.env.ADMIN_EMAIL ?? 'admin@technova.test',
    password: process.env.ADMIN_PASSWORD ?? 'Terra-Local-Pass-2026',
  } })).data.access_token;
  if (!token) throw new Error('Development admin login returned no access token.');
  const categoryMap = new Map((await request('/api/v1/categories')).data.items.map(c => [c.slug,c]));
  const attributeMap = new Map((await request('/api/v1/attributes')).data.items.map(a => [a.code,a]));
  for (const [code,name] of attributeSpecs) {
    if (!attributeMap.has(code)) attributeMap.set(code,(await request('/api/v1/attributes', { method:'POST',body:{code,name,value_type:'TEXT'} })).data);
    if (attributeMap.get(code).value_type !== 'TEXT') throw new Error(`Existing attribute ${code} is not TEXT; refusing to change it.`);
  }
  for (const [slug,name] of categories) {
    if (!categoryMap.has(slug)) {
      categoryMap.set(slug,(await request('/api/v1/categories',{method:'POST',body:{slug,name}})).data);
      stats.categories_created++;
    }
    const category = categoryMap.get(slug);
    for (const [code,,variant] of attributeSpecs) {
      const id = attributeMap.get(code).attribute_id;
      const mapping = category.attributes?.find(a => a.attribute_id === id);
      if (mapping && (mapping.is_variant !== variant || mapping.is_required)) throw new Error(`Incompatible category mapping: ${slug}/${code}`);
      if (!mapping) await request(`/api/v1/categories/${category.category_id}/attributes/${id}`,{method:'PUT',body:{is_required:false,is_variant:variant}});
    }
  }
  const summaries = new Map();
  let cursor;
  do {
    const page = (await request(`/api/v1/products?limit=100${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`)).data;
    page.items.forEach(p => summaries.set(p.slug,p));
    cursor = page.next_cursor;
  } while (cursor);

  for (const spec of [...existing,...products]) {
    const [categorySlug,slug,name,brand,base,configs,colors,description] = spec;
    const category_id = categoryMap.get(categorySlug).category_id;
    const skus = configs.map((config,i) => ({sku_code:`DEV-${slug.toUpperCase()}-${i+1}`,base_price_vnd:base+i*Math.max(100000,Math.round(base*0.07/10000)*10000),status:'ACTIVE',variant_attributes:{'mau-sac':colors[i],'cau-hinh':config}}));
    const summary = summaries.get(slug);
    let current;
    if (!summary) {
      current = await request('/api/v1/products',{method:'POST',body:{category_id,name,slug,description:`${description}\n\nDữ liệu mẫu cho môi trường phát triển. Tồn kho chưa được xác nhận.`,attributes:{'thuong-hieu':brand},skus}});
      stats.products_created++;
      stats.skus_added+=skus.length;
    } else {
      current = await request(`/api/v1/products/${summary.product_id}`);
      if (current.data.status !== 'ACTIVE' && current.data.status !== 'DRAFT') throw new Error(`Refusing to modify ${slug} in status ${current.data.status}.`);
      const id = current.data.product_id;
      // Preserve the original SKU code/price and add attributes to the first old SKU.
      const original = current.data.skus.find(s => !s.sku_code.startsWith('DEV-'));
      if (original) {
        if (!original.variant_attributes['mau-sac'] || !original.variant_attributes['cau-hinh']) {
          await request(`/api/v1/products/${id}/skus/${original.sku_id}`,{method:'PUT',etag:current.etag,body:{sku_code:original.sku_code,base_price_vnd:original.base_price_vnd,status:original.status,variant_attributes:{...original.variant_attributes,...skus[0].variant_attributes}}});
          current = await request(`/api/v1/products/${id}`);
        }
        skus.shift();
      }
      if (!current.data.attributes['thuong-hieu']) {
        await request(`/api/v1/products/${id}`,{method:'PUT',etag:current.etag,body:{category_id:current.data.category_id,name:current.data.name,slug:current.data.slug,description:current.data.description,attributes:{...current.data.attributes,'thuong-hieu':brand}}});
        current = await request(`/api/v1/products/${id}`);
      }
      for (const sku of skus) {
        if (current.data.skus.some(s => s.sku_code === sku.sku_code)) continue;
        await request(`/api/v1/products/${id}/skus`,{method:'POST',etag:current.etag,body:sku});
        stats.skus_added++;
        current = await request(`/api/v1/products/${id}`);
      }
    }
    if (current.data.status === 'DRAFT') await request(`/api/v1/products/${current.data.product_id}/status`,{method:'PATCH',etag:current.etag,body:{status:'ACTIVE'}});
    console.log(`✓ ${name}: ${current.data.skus.length} SKU`);
  }
  console.log(JSON.stringify(stats));
  if (process.argv.includes('--replay-outbox')) await replayOutbox(categoryMap);
}
main().catch(error => { console.error(error.message); process.exitCode=1; });
