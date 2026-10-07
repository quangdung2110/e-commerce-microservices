#!/usr/bin/env bash
# Tạo catalog mẫu trên stack docker-compose LOCAL qua đúng API quản trị của
# Product Service (đi qua gateway + gateway-auth), để thử giao diện admin.
#
#   DEV_ADMIN=1 frontend/scripts/create-dev-account.sh   # một lần, tạo tài khoản admin
#   frontend/scripts/seed-dev-catalog.sh
#
# Chạy lại được: danh mục/sản phẩm trùng slug bị Product trả 409 và được bỏ qua.
# CHỈ dành cho môi trường phát triển.
set -euo pipefail

GATEWAY="${GATEWAY:-http://localhost:8088}"
ADMIN_EMAIL="${ADMIN_EMAIL:-admin@technova.test}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-Terra-Local-Pass-2026}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

json_field() { sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p"; }

# Body luôn đi qua file: trên Git Bash/Windows, tham số `curl -d` chứa tiếng Việt
# bị đổi sang code page ANSI và server nhận JSON hỏng (400 INVALID_REQUEST).
body_file() { printf '%s' "$1" > "$TMP/body.json"; echo "@$TMP/body.json"; }

echo "→ Đăng nhập ${ADMIN_EMAIL}"
TOKEN=$(curl -s -X POST "${GATEWAY}/api/v1/auth/sessions" -H 'Content-Type: application/json' \
  -d "{\"email\":\"${ADMIN_EMAIL}\",\"password\":\"${ADMIN_PASSWORD}\"}" | json_field access_token)
[ -n "$TOKEN" ] || { echo "  Đăng nhập thất bại."; exit 1; }
AUTH=(-H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json')

# category <name> <slug> → in category_id (tạo mới hoặc tìm lại theo slug)
category() {
  local status
  status=$(curl -s -o "$TMP/cat.json" -w '%{http_code}' -X POST "${GATEWAY}/api/v1/categories" "${AUTH[@]}" \
    --data-binary "$(body_file "{\"name\":\"$1\",\"slug\":\"$2\"}")")
  if [ "$status" = "201" ]; then
    json_field category_id < "$TMP/cat.json" | head -1
  else
    curl -s "${GATEWAY}/api/v1/categories?root_only=true" "${AUTH[@]}" | tr '{' '\n' \
      | { grep "\"slug\":\"$2\"" || true; } | json_field category_id | head -1
  fi
}

# product <category_id> <name> <slug> <sku_code> <price> <target_status: DRAFT|ACTIVE|HIDDEN>
product() {
  local body status etag id
  body=$(printf '{"category_id":"%s","name":"%s","slug":"%s","description":"Sản phẩm mẫu cho môi trường phát triển.","attributes":{},"skus":[{"sku_code":"%s","base_price_vnd":%s,"status":"ACTIVE","variant_attributes":{}}]}' \
    "$1" "$2" "$3" "$4" "$5")
  status=$(curl -s -D "$TMP/h.txt" -o "$TMP/p.json" -w '%{http_code}' -X POST "${GATEWAY}/api/v1/products" "${AUTH[@]}" --data-binary "$(body_file "$body")")
  if [ "$status" != "201" ]; then
    echo "  ${3}: bỏ qua (HTTP ${status})"; return
  fi
  id=$(json_field product_id < "$TMP/p.json" | head -1)
  etag=$(grep -i '^etag:' "$TMP/h.txt" | cut -d' ' -f2- | tr -d '\r')
  echo "  ${3}: tạo ${id}"
  if [ "$6" != "DRAFT" ]; then
    status=$(curl -s -o /dev/null -w '%{http_code}' -X PATCH "${GATEWAY}/api/v1/products/${id}/status" "${AUTH[@]}" \
      -H "If-Match: ${etag}" -d "{\"status\":\"ACTIVE\"}")
    echo "    → ACTIVE (HTTP ${status})"
  fi
  if [ "$6" = "HIDDEN" ]; then
    etag=$(curl -s -D - -o /dev/null "${GATEWAY}/api/v1/products/${id}" "${AUTH[@]}" | grep -i '^etag:' | cut -d' ' -f2- | tr -d '\r')
    status=$(curl -s -o /dev/null -w '%{http_code}' -X PATCH "${GATEWAY}/api/v1/products/${id}/status" "${AUTH[@]}" \
      -H "If-Match: ${etag}" -d "{\"status\":\"HIDDEN\"}")
    echo "    → HIDDEN (HTTP ${status})"
  fi
}

echo "→ Danh mục"
PHONES=$(category "Điện thoại" "dien-thoai")
LAPTOPS=$(category "Laptop" "laptop")
AUDIO=$(category "Âm thanh" "am-thanh")
echo "  dien-thoai=${PHONES} laptop=${LAPTOPS} am-thanh=${AUDIO}"

echo "→ Sản phẩm"
product "$PHONES"  "Điện thoại Terra X1 128GB"    "dien-thoai-terra-x1"    "TX1-128"   12990000 ACTIVE
product "$PHONES"  "Điện thoại Terra X1 Pro"      "dien-thoai-terra-x1-pro" "TX1P-256"  18490000 ACTIVE
product "$PHONES"  "Điện thoại Terra Lite"        "dien-thoai-terra-lite"  "TL-64"      5490000 DRAFT
product "$LAPTOPS" "Laptop Nova Air 13"           "laptop-nova-air-13"     "NA13-16"   24990000 ACTIVE
product "$LAPTOPS" "Laptop Nova Pro 16"           "laptop-nova-pro-16"     "NP16-32"   42990000 HIDDEN
product "$AUDIO"   "Tai nghe không dây Terra Buds" "tai-nghe-terra-buds"   "TB-01"      1990000 ACTIVE

echo "✓ Xong. Xem: GET ${GATEWAY}/api/v1/products (cần token ADMIN)"
