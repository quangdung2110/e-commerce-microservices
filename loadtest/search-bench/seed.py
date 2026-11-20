"""Seeds an isolated Elasticsearch with a synthetic catalog for the Search scale benchmark.

    python seed.py --es http://elasticsearch:9200 --products 10000 --schema-dir /schema --out /out

Builds the same physical indices Search bootstraps (products_v1 and categories_v1 with their read and write
aliases) from the canonical schema files, then bulk-loads SKU documents shaped like CatalogProjection writes
them. Everything is generated from --seed, so a size always produces the same catalog.

Writes <out>/manifest.json: the scenario parameters and, for each one, the number of products that should
match (a product matches when its product-level conditions hold and at least one of its SKUs satisfies
every SKU-level condition, ADR-020). The k6 script checks total_products against these numbers.

Destructive for the target cluster: it deletes and recreates products_v1 and categories_v1, and deletes every
product_cards and search_jobs generation. Point it only
at the benchmark Elasticsearch, never at the shared compose stack. Standard library only.
"""
import argparse
import json
import random
import sys
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone

PRODUCTS_INDEX = "products_v1"
CATEGORIES_INDEX = "categories_v1"

# Four roots, four second-level nodes each, five leaves under each of those: 80 leaves. Products spread
# evenly over the leaves, so a root covers ~25% of the catalog and a leaf ~1.25%.
ROOTS = [
    ("thoi-trang", "Thời trang", ["Áo thun", "Áo sơ mi", "Quần jean", "Váy liền", "Giày lười"]),
    ("dien-tu", "Điện tử", ["Tai nghe", "Loa", "Sạc nhanh", "Bàn phím", "Chuột không dây"]),
    ("nha-cua", "Nhà cửa", ["Nồi chiên", "Đèn bàn", "Gối tựa", "Kệ sách", "Bình giữ nhiệt"]),
    ("the-thao", "Thể thao", ["Áo bóng đá", "Thảm tập", "Bóng rổ", "Vợt cầu lông", "Bình nước"]),
]
MIDS_PER_ROOT = 4
LEAVES_PER_MID = 5
ADJECTIVES = ["cao cấp", "cơ bản", "phiên bản mới", "bền đẹp", "nhẹ", "chính hãng", "giá tốt", "tiện dụng"]
BRANDS = ["hoa sen", "minh long", "sao viet", "bach dang", "truong son", "phuong nam", "dai duong", "kim cuong"]
RARE_BRAND = "zentaro"          # ~0.5% of products: the narrow full-text query
RARE_SHARE = 0.005
COLORS = ["den", "trang", "do", "xanh", "vang"]
SIZES = ["s", "m", "l", "xl"]
IN_STOCK_SHARE = 0.85            # per SKU
PROMOTION_SHARE = 0.20           # per SKU

FILTER_COLOR = "do"
FILTER_PRICE_MAX = 500_000


def request(method, url, body=None, content_type="application/json", ok=(200, 201)):
    data = None
    if body is not None:
        data = body if isinstance(body, bytes) else json.dumps(body).encode("utf-8")
    req = urllib.request.Request(url, data=data, method=method, headers={"Content-Type": content_type})
    try:
        with urllib.request.urlopen(req, timeout=300) as response:
            payload = response.read()
            return response.status, json.loads(payload) if payload else None
    except urllib.error.HTTPError as error:
        payload = error.read()
        if error.code in ok:
            return error.code, json.loads(payload) if payload else None
        raise RuntimeError(f"{method} {url} -> HTTP {error.code}: {payload[:500]!r}") from None


def wait_for_es(es, deadline_s=300):
    started = time.time()
    while True:
        try:
            _, health = request("GET", f"{es}/_cluster/health?wait_for_status=yellow&timeout=5s")
            if health and health.get("status") in ("yellow", "green"):
                return
        except Exception:  # noqa: BLE001 - the cluster is still starting
            pass
        if time.time() - started > deadline_s:
            sys.exit(f"Elasticsearch at {es} did not become ready in {deadline_s}s")
        time.sleep(2)


def load_schema(schema_dir, name):
    with open(f"{schema_dir}/{name}.index.json", encoding="utf-8") as handle:
        schema = json.load(handle)
    return {"settings": schema["settings"], "mappings": schema["mappings"]}


def recreate(es, index, schema, aliases):
    request("DELETE", f"{es}/{index}", ok=(200, 404))
    body = json.loads(json.dumps(schema))
    # One node: replicas could never be assigned. Refresh is off during the load and restored afterwards.
    body["settings"]["number_of_replicas"] = 0
    body["settings"]["refresh_interval"] = "-1"
    body["aliases"] = {alias: {} for alias in aliases}
    request("PUT", f"{es}/{index}", body)


def bulk(es, index, docs):
    lines = []
    for doc_id, doc in docs:
        lines.append(json.dumps({"index": {"_index": index, "_id": doc_id}}))
        lines.append(json.dumps(doc, ensure_ascii=False))
    payload = ("\n".join(lines) + "\n").encode("utf-8")
    _, result = request("POST", f"{es}/_bulk", payload, content_type="application/x-ndjson")
    if result.get("errors"):
        failed = next(item for item in result["items"] if item["index"].get("error"))
        raise RuntimeError(f"bulk error: {failed['index']['error']}")


def iso(moment):
    return moment.astimezone(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def build_categories(rng, now):
    """Returns (category documents, leaves). A leaf is (category document, root index, noun)."""
    docs = []
    leaves = []
    for root_index, (root_slug, root_name, nouns) in enumerate(ROOTS):
        root_id = str(uuid.UUID(int=rng.getrandbits(128)))
        docs.append(category_doc(root_id, root_name, root_slug, None, [root_id], [root_slug], False, now))
        for mid in range(MIDS_PER_ROOT):
            mid_id = str(uuid.UUID(int=rng.getrandbits(128)))
            mid_slug = f"{root_slug}-nhom-{mid + 1}"
            docs.append(category_doc(mid_id, f"{root_name} nhóm {mid + 1}", mid_slug, root_id,
                                     [root_id, mid_id], [root_slug, mid_slug], False, now))
            for leaf in range(LEAVES_PER_MID):
                leaf_id = str(uuid.UUID(int=rng.getrandbits(128)))
                noun = nouns[leaf]
                leaf_slug = f"{mid_slug}-{leaf + 1}"
                doc = category_doc(leaf_id, f"{noun} {mid + 1}", leaf_slug, mid_id, [root_id, mid_id, leaf_id],
                                   [root_slug, mid_slug, leaf_slug], True, now)
                docs.append(doc)
                leaves.append((doc, root_index, noun))
    return docs, leaves


def category_doc(category_id, name, slug, parent_id, ancestor_ids, ancestor_slugs, is_leaf, now):
    return {
        "category_id": category_id, "name": name, "slug": slug, "parent_id": parent_id,
        "ancestor_ids": ancestor_ids, "ancestor_slugs": ancestor_slugs, "level": len(ancestor_ids) - 1,
        "is_leaf": is_leaf, "facet_definitions": [
            {"code": "color", "label": "Màu sắc", "value_type": "SELECT"},
            {"code": "size", "label": "Kích cỡ", "value_type": "SELECT"},
        ],
        "product_count": 0, "product_count_updated_at": iso(now), "is_deleted": False, "deleted_at": None,
        "updated_at": iso(now), "category_indexed_at": iso(now), "category_revision": 1,
        "last_event_id": str(uuid.uuid4()),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--es", required=True)
    parser.add_argument("--products", type=int, required=True)
    parser.add_argument("--skus-min", type=int, default=1)
    parser.add_argument("--skus-max", type=int, default=5)
    parser.add_argument("--seed", type=int, default=20261016)
    parser.add_argument("--schema-dir", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--batch", type=int, default=3000, help="SKU documents per _bulk request")
    args = parser.parse_args()

    rng = random.Random(args.seed)
    now = datetime(2026, 10, 16, tzinfo=timezone.utc)
    es = args.es.rstrip("/")
    wait_for_es(es)

    # A fresh catalog also needs a fresh projection and job state: product_cards built from the previous catalog would
    # still name products_v1 as its source, and search_jobs holds the projector's lease. ES refuses wildcard deletes
    # by default, so delete them by name.
    for pattern in ("product_cards_v*", "search_jobs_v*"):
        _, indices = request("GET", f"{es}/_cat/indices/{pattern}?format=json&h=index")
        for entry in indices or []:
            request("DELETE", f"{es}/{entry['index']}", ok=(200, 404))

    recreate(es, PRODUCTS_INDEX, load_schema(args.schema_dir, "products"), ["products", "products_write"])
    recreate(es, CATEGORIES_INDEX, load_schema(args.schema_dir, "categories"), ["categories", "categories_write"])

    category_docs, leaves = build_categories(rng, now)
    bulk(es, CATEGORIES_INDEX, [(doc["category_id"], doc) for doc in category_docs])
    root_ids = [doc["category_id"] for doc in category_docs if doc["parent_id"] is None]
    root_slugs = [slug for slug, _, _ in ROOTS]

    expected = {"all": 0, "root_category": 0, "leaf_category": 0, "q_broad": 0, "q_narrow": 0, "filtered": 0,
                "home_newest": 0, "home_promotion": 0}
    target_leaf = leaves[0][0]                 # first leaf of the first root
    started = time.time()
    batch = []
    sku_docs = 0
    for n in range(args.products):
        leaf, root_index, noun = leaves[n % len(leaves)]
        product_id = str(uuid.UUID(int=rng.getrandbits(128)))
        rare = rng.random() < RARE_SHARE
        brand = RARE_BRAND if rare else rng.choice(BRANDS)
        name = f"{noun} {rng.choice(ADJECTIVES)} {brand} {n:06d}"
        slug = f"sp-{n:06d}"
        rating = round(rng.uniform(3.0, 5.0), 2) if rng.random() < 0.7 else None
        rating_count = rng.randint(1, 900) if rating is not None else 0
        updated = now - timedelta(minutes=rng.randint(0, 180 * 24 * 60))
        base_tier = rng.choice([90_000, 190_000, 390_000, 790_000, 1_490_000, 3_990_000])

        any_stock = any_filtered = any_promotion = False
        for s in range(rng.randint(args.skus_min, args.skus_max)):
            sku_id = str(uuid.UUID(int=rng.getrandbits(128)))
            color, size = rng.choice(COLORS), rng.choice(SIZES)
            base = base_tier + rng.randint(0, 9) * 10_000
            promo = rng.random() < PROMOTION_SHARE
            selling = base if not promo else base * rng.choice([70, 80, 90, 95]) // 100
            in_stock = rng.random() < IN_STOCK_SHARE
            any_stock |= in_stock
            any_promotion |= promo
            any_filtered |= in_stock and color == FILTER_COLOR and selling <= FILTER_PRICE_MAX
            variant_tokens = [f"color:s:{color}", f"size:s:{size}"]
            batch.append((sku_id, {
                "sku_id": sku_id, "sku_code": f"{slug}-{s + 1}", "product_id": product_id, "product_slug": slug,
                "category_id": leaf["category_id"], "category_slug": leaf["slug"], "category_name": leaf["name"],
                "category_ancestor_ids": leaf["ancestor_ids"], "category_slug_path": leaf["ancestor_slugs"],
                "name": name, "description": f"Sản phẩm mẫu số {n} dùng cho đo hiệu năng tìm kiếm.",
                "thumbnail_storage_key": f"products/{product_id}/thumb.jpg",
                "product_status": "ACTIVE", "sku_status": "ACTIVE",
                "product_attribute_tokens": [], "variant_attribute_tokens": variant_tokens,
                "filter_tokens": variant_tokens,
                "base_price_vnd": base, "selling_price_vnd": selling, "has_promotion": promo,
                "is_in_stock": in_stock,
                "product_rating_avg": rating, "product_rating_count": rating_count,
                "is_deleted": False, "deleted_at": None, "updated_at": iso(updated),
                "catalog_indexed_at": iso(now), "availability_observed_at": iso(now),
                "availability_indexed_at": iso(now), "review_indexed_at": iso(now), "indexed_at": iso(now),
                "catalog_revision": 1, "availability_revision": 1, "review_revision": 1,
                "last_catalog_event_id": str(uuid.UUID(int=rng.getrandbits(128))),
                "last_availability_event_id": None, "last_review_event_id": None,
            }))
            if len(batch) >= args.batch:
                bulk(es, PRODUCTS_INDEX, batch)
                sku_docs += len(batch)
                batch = []

        # The home page sends in_stock=false (frontend/src/app/(site)/page.tsx): every product matches "newest",
        # and every product with a promoted SKU matches "promotion", in stock or not.
        expected["home_newest"] += 1
        if any_promotion:
            expected["home_promotion"] += 1
        # Every other scenario keeps the default in_stock=true, so a product needs an in-stock SKU to match.
        if any_stock:
            expected["all"] += 1
            if root_index == 0:
                expected["root_category"] += 1
            if leaf is target_leaf:
                expected["leaf_category"] += 1
            if noun.startswith("Áo"):
                expected["q_broad"] += 1
            if rare:
                expected["q_narrow"] += 1
        if any_filtered and root_index == 0:
            expected["filtered"] += 1

    if batch:
        bulk(es, PRODUCTS_INDEX, batch)
        sku_docs += len(batch)

    for index in (PRODUCTS_INDEX, CATEGORIES_INDEX):
        request("PUT", f"{es}/{index}/_settings", {"index": {"refresh_interval": "1s" if index == PRODUCTS_INDEX else "5s"}})
        request("POST", f"{es}/{index}/_refresh")
    _, count = request("GET", f"{es}/{PRODUCTS_INDEX}/_count")
    if count["count"] != sku_docs:
        sys.exit(f"indexed {count['count']} SKU documents, expected {sku_docs}")

    manifest = {
        "products": args.products, "sku_documents": sku_docs, "seed": args.seed,
        "params": {
            "root_category": root_slugs[0], "root_category_id": root_ids[0],
            "leaf_category": target_leaf["slug"], "q_broad": "áo", "q_narrow": RARE_BRAND,
            "filter": f"color:s:{FILTER_COLOR}", "price_max": FILTER_PRICE_MAX,
        },
        "expected_total_products": expected,
        "seconds": round(time.time() - started, 1),
    }
    with open(f"{args.out}/manifest.json", "w", encoding="utf-8") as handle:
        json.dump(manifest, handle, ensure_ascii=False, indent=2)
    print(json.dumps(manifest, ensure_ascii=False))


if __name__ == "__main__":
    main()
