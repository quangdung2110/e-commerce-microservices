"""Checks that product_cards follows the SKU index (ADR-027) on the isolated benchmark stack.

    python projection_check.py --es http://elasticsearch:9200 --search http://search-service-cards:8080

Writes to the benchmark Elasticsearch only, the way the indexing pipeline does (every write stamps indexed_at):

  1. a price change on one SKU reaches its card;
  2. tombstoning every SKU of a Product removes its card, and the search no longer returns it;
  3. a full reindex that moves the products alias to a new generation makes the projector rebuild product_cards from it,
     and searches keep answering throughout.

Never point it at the shared stack. Standard library only. Exits 1 on the first failed check.
"""
import argparse
import json
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone

ES = SEARCH = ""


def call(method, url, body=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, method=method, headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=300) as response:
            raw = response.read()
            return response.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as error:
        raw = error.read()
        return error.code, json.loads(raw) if raw else None


def now_iso():
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def wait_until(what, predicate, timeout=60):
    started = time.time()
    while time.time() - started < timeout:
        if predicate():
            print(f"ok   {what} ({time.time() - started:.1f} s)")
            return
        time.sleep(0.5)
    print(f"FAIL {what}: not within {timeout} s")
    sys.exit(1)


def card(product_id):
    status, body = call("GET", f"{ES}/product_cards/_doc/{product_id}")
    return body if status == 200 and body.get("found") else None


def skus_of(product_id):
    _, body = call("POST", f"{ES}/products/_search", {"size": 100, "query": {"term": {"product_id": product_id}}})
    return body["hits"]["hits"]


def main():
    global ES, SEARCH
    parser = argparse.ArgumentParser()
    parser.add_argument("--es", required=True)
    parser.add_argument("--search", required=True)
    args = parser.parse_args()
    ES, SEARCH = args.es.rstrip("/"), args.search.rstrip("/")

    _, page = call("GET", f"{SEARCH}/api/v2/search/products?size=1&sort=price_desc&in_stock=false")
    product_id = page["items"][0]["product_id"]
    skus = skus_of(product_id)
    target = skus[0]["_id"]
    print(f"product {product_id} with {len(skus)} SKUs")

    # 1. price change
    call("POST", f"{ES}/products/_update/{target}", {"doc": {"selling_price_vnd": 1234, "indexed_at": now_iso()}})
    wait_until("price change reaches the card", lambda: any(
        s["sku_id"] == target and s["selling_price_vnd"] == 1234 for s in (card(product_id) or {}).get("_source", {}).get("skus", [])))

    # 2. tombstone every SKU of the Product
    for sku in skus:
        call("POST", f"{ES}/products/_update/{sku['_id']}",
             {"doc": {"is_deleted": True, "deleted_at": now_iso(), "indexed_at": now_iso()}})
    wait_until("tombstoned Product loses its card", lambda: card(product_id) is None)

    def gone_from_search():
        _, result = call("GET", f"{SEARCH}/api/v2/search/products?size=100&sort=price_desc&in_stock=false")
        return all(item["product_id"] != product_id for item in result["items"])
    wait_until("search no longer returns it", gone_from_search)

    # 3. full reindex to a new products generation, then alias cutover
    _, aliases = call("GET", f"{ES}/_alias/products")
    old_index = next(iter(aliases))
    new_index = "products_v" + str(int(old_index.rsplit("_v", 1)[1]) + 1)
    _, mapping = call("GET", f"{ES}/{old_index}")
    definition = mapping[old_index]
    settings = {k: v for k, v in definition["settings"]["index"].items() if k in ("number_of_shards", "analysis")}
    settings["number_of_replicas"] = 0
    call("PUT", f"{ES}/{new_index}", {"settings": settings, "mappings": definition["mappings"]})
    status, _ = call("POST", f"{ES}/_reindex?refresh=true", {"source": {"index": old_index}, "dest": {"index": new_index}})
    if status != 200:
        print(f"FAIL reindex answered {status}")
        sys.exit(1)
    call("POST", f"{ES}/_aliases", {"actions": [
        {"remove": {"index": old_index, "alias": "products"}}, {"remove": {"index": old_index, "alias": "products_write"}},
        {"add": {"index": new_index, "alias": "products"}}, {"add": {"index": new_index, "alias": "products_write"}}]})
    print(f"moved products alias {old_index} -> {new_index}")

    failures = []

    def rebuilt():
        _, current = call("GET", f"{ES}/_alias/product_cards")
        physical = next(iter(current or {}), None)
        if physical is None:
            return False
        _, meta = call("GET", f"{ES}/{physical}/_mapping")
        status, result = call("GET", f"{SEARCH}/api/v2/search/products?size=5")
        if status != 200:
            failures.append(status)
        return meta[physical]["mappings"].get("_meta", {}).get("source_index") == new_index
    wait_until("projector rebuilt product_cards from the new generation", rebuilt, timeout=180)
    print(f"ok   searches answered 200 throughout the cutover ({len(failures)} non-200)" if not failures
          else f"FAIL {len(failures)} non-200 answers during the cutover: {failures[:5]}")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
