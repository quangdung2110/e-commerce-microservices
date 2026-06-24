const ES_URL = 'http://localhost:9200';

const crypto = require('crypto');

const TARGET_SKUS = 1000000;
const BATCH_SIZE = 5000;

const categories = [
  { id: 'c-smartphone', name: 'Smartphone', path: 'Electronics > Smartphone' },
  { id: 'c-laptop', name: 'Laptop', path: 'Electronics > Laptop' },
  { id: 'c-tablet', name: 'Tablet', path: 'Electronics > Tablet' },
  { id: 'c-smartwatch', name: 'Smartwatch', path: 'Electronics > Smartwatch' },
  { id: 'c-headphone', name: 'Headphone', path: 'Electronics > Audio > Headphone' },
];

const brands = {
  'c-smartphone': ['iPhone', 'Samsung Galaxy', 'Xiaomi', 'Oppo', 'Sony Xperia'],
  'c-laptop': ['MacBook Pro', 'MacBook Air', 'Dell XPS', 'Asus ROG', 'Lenovo ThinkPad'],
  'c-tablet': ['iPad Pro', 'iPad Air', 'Samsung Galaxy Tab', 'Xiaomi Pad'],
  'c-smartwatch': ['Apple Watch', 'Galaxy Watch', 'Garmin', 'Amazfit'],
  'c-headphone': ['AirPods', 'Sony WH', 'Bose QuietComfort', 'Sennheiser'],
};

const attrs = {
  'c-smartphone': ['Color: Black', 'Color: White', 'Storage: 128GB', 'Storage: 256GB', 'RAM: 8GB', 'RAM: 12GB'],
  'c-laptop': ['Color: Silver', 'Color: Space Gray', 'Storage: 512GB', 'Storage: 1TB', 'RAM: 16GB', 'RAM: 32GB', 'CPU: Core i7', 'CPU: M3 Max'],
  'c-tablet': ['Color: Silver', 'Color: Gray', 'Storage: 128GB', 'Storage: 256GB'],
  'c-smartwatch': ['Size: 41mm', 'Size: 45mm', 'Color: Midnight', 'Color: Starlight'],
  'c-headphone': ['Color: Black', 'Color: White', 'Type: Over-ear', 'Type: In-ear'],
};

function randomInt(min, max) {
  return Math.floor(Math.random() * (max - min + 1)) + min;
}

function generateCategoriesBulk() {
  let bulk = '';
  for (const cat of categories) {
    bulk += JSON.stringify({ index: { _index: 'categories_v1', _id: cat.id } }) + '\n';
    bulk += JSON.stringify({
      category_id: cat.id,
      name: cat.name,
      path: cat.path,
      level: cat.path.split(' > ').length,
      parent_id: 'c-electronics',
      is_active: true,
      display_order: 1,
      indexed_at: new Date().toISOString(),
      last_event_id: crypto.randomUUID()
    }) + '\n';
  }
  return bulk;
}

function generateProductsBulk(count, startIndex) {
  let bulk = '';
  for (let i = 0; i < count; i++) {
    const cat = categories[randomInt(0, categories.length - 1)];
    const brand = brands[cat.id][randomInt(0, brands[cat.id].length - 1)];
    const attrList = attrs[cat.id];
    
    const skuId = crypto.randomUUID();
    const productId = `prod-${randomInt(1, 200000)}`; // Multiple SKUs per product
    const name = `${brand} Model ${randomInt(100, 999)}`;
    const basePrice = randomInt(100, 2000) * 10000;
    
    // Pick 2 random attributes
    const a1 = attrList[randomInt(0, attrList.length - 1)];
    const a2 = attrList[randomInt(0, attrList.length - 1)];
    const searchableAttributes = Array.from(new Set([a1, a2]));

    bulk += JSON.stringify({ index: { _index: 'products_v1', _id: skuId } }) + '\n';
    bulk += JSON.stringify({
      sku_id: skuId,
      product_id: productId,
      category_id: cat.id,
      category_name: cat.name,
      category_path: cat.path,
      name: name,
      description: `Description for ${name}`,
      thumbnail_url: `https://example.com/img/${skuId}.jpg`,
      product_status: 'ACTIVE',
      sku_status: 'ACTIVE',
      searchable_attributes: searchableAttributes,
      variant_attributes: searchableAttributes,
      original_price: basePrice + 500000,
      promotional_price: basePrice,
      best_price: basePrice,
      is_in_stock: true,
      has_promotion: true,
      is_deleted: false,
      product_rating_avg: (randomInt(30, 50) / 10),
      product_rating_count: randomInt(0, 1000),
      created_at: new Date().toISOString(),
      updated_at: new Date().toISOString(),
      indexed_at: new Date().toISOString(),
      last_event_id: crypto.randomUUID(),
      version: new Date().toISOString()
    }) + '\n';
  }
  return bulk;
}

async function sendBulk(bulkData) {
  try {
    const res = await fetch(`${ES_URL}/_bulk`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-ndjson' },
      body: bulkData
    });
    if (!res.ok) {
      console.error('Bulk insert failed with status:', res.status);
      const text = await res.text();
      console.error(text.substring(0, 500));
    }
  } catch (err) {
    console.error('Network error during bulk insert:', err.message);
  }
}

async function run() {
  console.log('Seeding categories...');
  await sendBulk(generateCategoriesBulk());
  
  console.log(`Starting to seed ${TARGET_SKUS} products in batches of ${BATCH_SIZE}...`);
  let seeded = 0;
  
  while (seeded < TARGET_SKUS) {
    const currentBatch = Math.min(BATCH_SIZE, TARGET_SKUS - seeded);
    const bulkData = generateProductsBulk(currentBatch, seeded);
    
    await sendBulk(bulkData);
    seeded += currentBatch;
    
    if (seeded % 50000 === 0) {
      console.log(`Seeded ${seeded} / ${TARGET_SKUS} SKUs...`);
    }
  }
  console.log('Seeding completed!');
}

run();
