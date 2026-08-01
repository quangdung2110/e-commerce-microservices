#!/bin/bash
# wait-for-db.sh
# Script để chờ PostgreSQL sẵn sàng trước khi khởi động application
# Sử dụng: ./wait-for-db.sh [command to run]

set -e

host="${DB_HOST:-localhost}"
port="${DB_PORT:-5432}"
user="${DB_USER:-postgres}"
password="${DB_PASSWORD:-postgres}"
database="${DB_NAME:-user_service}"
max_attempts="${MAX_ATTEMPTS:-30}"
attempt=0

echo "=========================================="
echo "Waiting for PostgreSQL..."
echo "Host: $host:$port"
echo "Database: $database"
echo "=========================================="

until PGPASSWORD="$password" psql -h "$host" -p "$port" -U "$user" -d "$database" -c '\q' 2>/dev/null; do
    attempt=$((attempt + 1))

    if [ $attempt -ge $max_attempts ]; then
        echo ""
        echo "ERROR: PostgreSQL did not become ready after $max_attempts attempts"
        echo "Please check your database configuration"
        exit 1
    fi

    echo "PostgreSQL is unavailable - sleeping (attempt $attempt/$max_attempts)"
    sleep 2
done

echo ""
echo "=========================================="
echo "PostgreSQL is up - continuing"
echo "=========================================="

# Nếu có command được truyền vào, thực thi nó
if [ $# -gt 0 ]; then
    exec "$@"
fi
