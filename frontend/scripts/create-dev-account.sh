#!/usr/bin/env bash
# Tạo một tài khoản đã xác minh trên stack docker-compose LOCAL để thử giao diện.
#
#   frontend/scripts/create-dev-account.sh                 # tài khoản mặc định bên dưới
#   DEV_EMAIL=a@dev.test DEV_PASSWORD=… frontend/scripts/create-dev-account.sh
#   DEV_ADMIN=1 frontend/scripts/create-dev-account.sh     # tài khoản quản trị mặc định
#
# DEV_ADMIN=1 cấp thêm role ADMIN bằng cách ghi thẳng vào auth.account_roles,
# giống docs/auth/schema/seed-admin.sql.example — Auth không có API cấp role.
#
# Bình thường OTP đi Auth → Kafka → Notification → email. Ở local không cần dựng
# Notification: Auth lưu nội dung delivery (gồm OTP) trong Redis để Notification
# lấy đi, script đọc thẳng từ đó rồi gọi API xác minh như người dùng thật.
#
# CHỈ dành cho môi trường phát triển. Đây là thông tin đăng nhập thử, không phải
# tài khoản thật; không dùng lại ở bất kỳ môi trường nào khác.
set -euo pipefail

GATEWAY="${GATEWAY:-http://localhost:8088}"
DEV_ADMIN="${DEV_ADMIN:-0}"
if [ "$DEV_ADMIN" = "1" ]; then
  DEV_EMAIL="${DEV_EMAIL:-admin@technova.test}"
else
  DEV_EMAIL="${DEV_EMAIL:-buyer@technova.test}"
fi
# Chính sách Auth: 12–128 ký tự và không được chứa phần tên của email.
DEV_PASSWORD="${DEV_PASSWORD:-Terra-Local-Pass-2026}"

grant_admin() {
  echo "→ Cấp role ADMIN"
  docker exec postgres psql -U postgres -d auth_db -v ON_ERROR_STOP=1 -q -c \
    "INSERT INTO auth.account_roles (account_id, role_code)
     SELECT id, 'ADMIN' FROM auth.accounts WHERE email_normalized = lower(btrim('${DEV_EMAIL}'))
     ON CONFLICT DO NOTHING;"
  echo "  Role có hiệu lực từ access token kế tiếp (đăng nhập lại)."
}

echo "→ Đăng ký ${DEV_EMAIL}"
status=$(curl -s -o /tmp/register.json -w '%{http_code}' -X POST "${GATEWAY}/api/v1/auth/registrations" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"${DEV_EMAIL}\",\"password\":\"${DEV_PASSWORD}\",\"confirm_password\":\"${DEV_PASSWORD}\"}")
case "$status" in
  202|201|200) ;;
  409)
    echo "  Email đã tồn tại — dùng mật khẩu đã cấu hình."
    [ "$DEV_ADMIN" = "1" ] && grant_admin
    exit 0 ;;
  *) echo "  Đăng ký thất bại (HTTP ${status}):"; cat /tmp/register.json; echo; exit 1 ;;
esac

echo "→ Lấy OTP từ Redis (auth:delivery:*)"
otp=""
for _ in 1 2 3 4 5; do
  for key in $(docker exec redis redis-cli --scan --pattern 'auth:delivery:*'); do
    record=$(docker exec redis redis-cli GET "$key")
    if printf '%s' "$record" | grep -q "\"${DEV_EMAIL}\""; then
      otp=$(printf '%s' "$record" | sed -n 's/.*"otp":"\([0-9]\{6\}\)".*/\1/p')
      [ -n "$otp" ] && break 2
    fi
  done
  sleep 1
done
if [ -z "$otp" ]; then
  echo "  Không tìm thấy OTP cho ${DEV_EMAIL} trong Redis."; exit 1
fi

echo "→ Xác minh email"
status=$(curl -s -o /tmp/verify.json -w '%{http_code}' -X POST "${GATEWAY}/api/v1/auth/email-verifications" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"${DEV_EMAIL}\",\"otp\":\"${otp}\"}")
if [ "$status" != "204" ] && [ "$status" != "200" ]; then
  echo "  Xác minh thất bại (HTTP ${status}):"; cat /tmp/verify.json; echo; exit 1
fi

[ "$DEV_ADMIN" = "1" ] && grant_admin
echo "✓ Tài khoản ${DEV_EMAIL} đã sẵn sàng."
