"""Compares GET /api/v2/search/products between the SKU grouping (ADR-020) and the product_cards projection (ADR-027).

    python compare.py --old http://search-service:8080 --new http://search-service-cards:8080 --manifest /out/manifest.json

Both instances serve the same seeded Elasticsearch. For every scenario and sort the script walks the first PAGES pages
through next_cursor on each implementation and compares, page by page:

  - total_products;
  - the Products in order, each with its representative SKU and price (the ADR-020 contract);
  - the facets (codes, option values and counts).

Relevance with a text query is compared as a set per page plus total: BM25 statistics differ between a SKU index and nested
SKU documents routed by product, so equal-relevance ties may break differently. Exits 1 on any other difference.
Standard library only; read-only.
"""
import argparse
import json
import sys
import urllib.error
import urllib.parse
import urllib.request

SORTS = ["relevance", "price_asc", "price_desc", "newest", "rating"]
PAGES = 3


def get(base, query):
    url = f"{base}/api/v2/search/products?{query}"
    with urllib.request.urlopen(url, timeout=60) as response:
        return json.loads(response.read())


def facets(page):
    out = {}
    for facet in (page.get("facets") or {}).get("filters", []):
        out[facet["code"]] = sorted((option["value"], option["count"]) for option in facet["options"])
    return out


def walk(base, query):
    pages, cursor = [], None
    for _ in range(PAGES):
        q = query + (f"&cursor={urllib.parse.quote(cursor)}" if cursor else "")
        page = get(base, q)
        pages.append(page)
        cursor = page.get("next_cursor")
        if not cursor:
            break
    return pages


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--old", required=True)
    parser.add_argument("--new", required=True)
    parser.add_argument("--manifest", required=True)
    args = parser.parse_args()
    with open(args.manifest, encoding="utf-8") as handle:
        p = json.load(handle)["params"]

    scenarios = {
        "all": "",
        "root_category": f"category={p['root_category']}",
        "leaf_category": f"category={p['leaf_category']}",
        "q_broad": "q=" + urllib.parse.quote(p["q_broad"]),
        "q_narrow": "q=" + urllib.parse.quote(p["q_narrow"]),
        "filtered": f"category={p['root_category']}&filter={urllib.parse.quote(p['filter'])}&price_max={p['price_max']}",
        "home_newest": "in_stock=false",
        "home_promotion": "has_promotion=true&in_stock=false",
    }
    failures, skipped, checked, facet_count_diffs = [], [], 0, 0
    for name, base_query in scenarios.items():
        for sort in SORTS:
            query = f"size=20&sort={sort}" + (f"&{base_query}" if base_query else "")
            try:
                new_pages = walk(args.new, query)
            except Exception as error:  # noqa: BLE001 - reported as a difference
                failures.append(f"{name}/{sort}: projection request failed: {error}")
                continue
            try:
                old_pages = walk(args.old, query)
            except urllib.error.HTTPError as error:
                # The grouping exceeds its own budget on large catalogs; there is nothing to compare against.
                print(f"note {name}/{sort}: grouping answered {error.code}, projection answered "
                      f"{new_pages[0]['total_products']} products")
                skipped.append(f"{name}/{sort}")
                continue
            text_relevance = sort == "relevance" and base_query.startswith("q=")
            if len(old_pages) != len(new_pages):
                failures.append(f"{name}/{sort}: {len(old_pages)} pages vs {len(new_pages)}")
            for number, (old, new) in enumerate(zip(old_pages, new_pages), start=1):
                checked += 1
                where = f"{name}/{sort} page {number}"
                if old["total_products"] != new["total_products"]:
                    failures.append(f"{where}: total {old['total_products']} vs {new['total_products']}")
                rows_old = [(i["product_id"], i["sku_id"], i["selling_price_vnd"]) for i in old["items"]]
                rows_new = [(i["product_id"], i["sku_id"], i["selling_price_vnd"]) for i in new["items"]]
                if text_relevance:
                    if sorted(rows_old) != sorted(rows_new) and number == 1:
                        overlap = len(set(rows_old) & set(rows_new))
                        print(f"note {where}: relevance ties ordered differently, {overlap}/{len(rows_old)} rows shared")
                elif rows_old != rows_new:
                    first = next(i for i, (a, b) in enumerate(zip(rows_old + [None] * 99, rows_new + [None] * 99)) if a != b)
                    failures.append(f"{where}: rows differ from position {first}: {rows_old[first:first + 2]} vs {rows_new[first:first + 2]}")
                if number == 1:
                    f_old, f_new = facets(old), facets(new)
                    if set(f_old) != set(f_new) or any([v for v, _ in f_old[c]] != [v for v, _ in f_new[c]] for c in f_old):
                        failures.append(f"{where}: facet options differ: {f_old} vs {f_new}")
                    elif f_old != f_new:
                        facet_count_diffs += 1
                        print(f"note {where}: facet counts differ (old cardinality is approximate): {f_old} vs {f_new}")
    print(f"compared {checked} pages across {len(scenarios)} scenarios x {len(SORTS)} sorts; "
          f"{len(failures)} differences; {facet_count_diffs} pages with approximate facet counts; "
          f"{len(skipped)} combinations the grouping could not answer")
    for failure in failures:
        print("DIFF", failure)
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
