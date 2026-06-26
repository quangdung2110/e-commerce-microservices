import fs from 'fs';

const ES_URL = 'http://localhost:9200';

async function waitForES() {
  console.log("Đợi Elasticsearch khởi động...");
  for (let i = 0; i < 30; i++) {
    try {
      const res = await fetch(`${ES_URL}/_cluster/health`);
      if (res.ok) {
        console.log("Elasticsearch đã sẵn sàng!");
        return true;
      }
    } catch (e) {
      await new Promise(r => setTimeout(r, 2000));
    }
  }
  throw new Error("Timeout khi kết nối Elasticsearch");
}

async function createIndex(indexName, mapping) {
  try {
    // Delete existing index
    await fetch(`${ES_URL}/${indexName}`, { method: 'DELETE' });
    
    const res = await fetch(`${ES_URL}/${indexName}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(mapping),
    });
    const data = await res.json();
    if (res.ok || data.error?.type === 'resource_already_exists_exception') {
      console.log(`Index ${indexName} đã sẵn sàng.`);
    } else {
      console.error(`Lỗi tạo index ${indexName}:`, data);
    }
  } catch (e) {
    console.error(`Lỗi tạo index ${indexName}:`, e);
  }
}

async function createAlias(indexName, aliasName) {
  try {
    await fetch(`${ES_URL}/_aliases`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        actions: [{ add: { index: indexName, alias: aliasName } }]
      })
    });
    console.log(`Đã tạo alias ${aliasName} -> ${indexName}`);
  } catch (e) {
    console.error(`Lỗi tạo alias ${aliasName}:`, e);
  }
}

async function bulkInsert(indexName, documents) {
  const body = documents.flatMap(doc => [
    { index: { _index: indexName, _id: doc.sku_id || doc.category_id || doc.suggestion_id } },
    doc
  ]).map(line => JSON.stringify(line)).join('\n') + '\n';

  const res = await fetch(`${ES_URL}/${indexName}/_bulk`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-ndjson' },
    body: body
  });
  if (res.ok) {
    console.log(`Đã chèn ${documents.length} bản ghi vào ${indexName}.`);
  } else {
    console.error(`Lỗi chèn dữ liệu vào ${indexName}`);
  }
}

async function run() {
  await waitForES();

  // 1. PRODUCTS
  const productMapping = {
    settings: {
      number_of_shards: 1,
      number_of_replicas: 0,
      analysis: {
        analyzer: {
          vietnamese_analyzer: { type: "standard" }, // Mock standard since ICU might not be fully configured locally
          autocomplete_analyzer: {
            type: "custom",
            tokenizer: "autocomplete_tokenizer",
            filter: ["lowercase"]
          },
          autocomplete_search_analyzer: {
            type: "custom",
            tokenizer: "standard",
            filter: ["lowercase"]
          }
        },
        tokenizer: {
          autocomplete_tokenizer: {
            type: "edge_ngram",
            min_gram: 2,
            max_gram: 20,
            token_chars: ["letter", "digit"]
          }
        }
      }
    },
    mappings: {
      properties: {
        sku_id: { type: "keyword" },
        product_id: { type: "keyword" },
        category_id: { type: "keyword" },
        name: {
          type: "text",
          analyzer: "vietnamese_analyzer",
          fields: {
            autocomplete: { type: "text", analyzer: "autocomplete_analyzer", search_analyzer: "autocomplete_search_analyzer" },
            keyword: { type: "keyword" }
          }
        },
        searchable_attributes: { type: "keyword" },
        variant_attributes: { type: "keyword" },
        best_price: { type: "scaled_float", scaling_factor: 100 },
        is_in_stock: { type: "boolean" },
        has_promotion: { type: "boolean" },
        is_deleted: { type: "boolean" },
        product_status: { type: "keyword" },
        sku_status: { type: "keyword" },
        product_rating_avg: { type: "half_float" },
        product_rating_count: { type: "integer" },
        updated_at: { type: "date" }
      }
    }
  };

  await createIndex('products_v1', productMapping);
  await createAlias('products_v1', 'products');
  await createAlias('products_v1', 'products_write');

  const products = Array.from({ length: 50 }).map((_, i) => ({
    sku_id: `sku-10${i}`,
    product_id: `prod-10${i}`,
    category_id: "cat-smartphone",
    name: `Điện thoại SmartX ${i} Pro`,
    thumbnail_url: "https://example.com/img.jpg",
    product_status: "ACTIVE",
    sku_status: "ACTIVE",
    searchable_attributes: [`brand:${i % 2 === 0 ? 'apple' : 'samsung'}`],
    variant_attributes: ["mausac:đen"],
    original_price: 20000000 + i * 100000,
    best_price: 18000000 + i * 100000,
    discount_percentage: 10,
    product_rating_avg: 4.8,
    product_rating_count: 100 + i,
    updated_at: new Date(Date.now() - i * 10000).toISOString(),
    is_in_stock: true,
    has_promotion: true,
    is_deleted: false,
    version: new Date().toISOString()
  }));
  await bulkInsert('products_v1', products);

  // 2. CATEGORIES
  const categoryMapping = {
    settings: { number_of_shards: 1, number_of_replicas: 0 },
    mappings: {
      properties: {
        category_id: { type: "keyword" },
        name: { type: "text" },
        product_count: { type: "long" }
      }
    }
  };
  await createIndex('categories_v1', categoryMapping);
  await createAlias('categories_v1', 'categories');

  const categories = [
    { category_id: "cat-smartphone", name: "Điện thoại", product_count: 50, level: 1, is_deleted: false, version: new Date().toISOString() },
    { category_id: "cat-laptop", name: "Laptop", product_count: 20, level: 1, is_deleted: false, version: new Date().toISOString() }
  ];
  await bulkInsert('categories_v1', categories);

  // 3. SUGGESTIONS
  const suggestMapping = {
    settings: { number_of_shards: 1, number_of_replicas: 0 },
    mappings: {
      properties: {
        suggestion_id: { type: "keyword" },
        text: { type: "text", fields: { keyword: { type: "keyword" } } },
        type: { type: "keyword" },
        weight: { type: "long" }
      }
    }
  };
  await createIndex('search_suggestions_v1', suggestMapping);
  await createAlias('search_suggestions_v1', 'search_suggestions');

  const suggestions = [
    { suggestion_id: "sgg-1", text: "điện thoại thông minh", type: "CATEGORY", weight: 1000 },
    { suggestion_id: "sgg-2", text: "điện thoại apple", type: "PRODUCT", weight: 900 }
  ];
  await bulkInsert('search_suggestions_v1', suggestions);

  console.log("Hoàn tất đẩy dữ liệu mock!");
}

run().catch(console.error);
