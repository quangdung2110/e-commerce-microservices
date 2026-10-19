-- Database of the service under test; Inventory migrates its own schema with Flyway on start.
CREATE DATABASE inventory_service;
\connect inventory_service
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
