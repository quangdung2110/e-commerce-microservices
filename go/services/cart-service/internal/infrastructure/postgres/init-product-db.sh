#!/bin/bash
# This script runs BEFORE the migrations (001_initial_schema.up.sql)
# It creates the product_service database needed by the Java product-service

set -e

echo "Creating product_service database if not exists..."
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname="$POSTGRES_DB" <<-EOSQL
    SELECT 'CREATE DATABASE product_service' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'product_service');
EOSQL

echo "product_service database ready."
