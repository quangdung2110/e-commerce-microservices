# Inventory Service Debezium CDC Configuration

This folder contains the configuration for Debezium CDC Connector to stream outbox events from PostgreSQL to Kafka.

## 1. Prerequisites

Before registering the connector, ensure the following steps are done on the PostgreSQL database:

### Create Publication
```sql
CREATE PUBLICATION inventory_publication FOR TABLE public.inventory_outbox;
```

### Create Logical Replication Slot
```sql
SELECT * FROM pg_create_logical_replication_slot('inventory_slot', 'pgoutput');
```

## 2. Registering the Connector

Once the database is ready, you can register the connector using the Kafka Connect REST API:

```bash
curl -X POST http://<kafka-connect-host>:8083/connectors \
  -H "Content-Type: application/json" \
  -d @inventory-postgres-connector.json
```

## 3. Verifying the Connector

Check the status of the connector to ensure it is running:
```bash
curl http://<kafka-connect-host>:8083/connectors/inventory-postgres-connector/status
```

## 4. Notes
- Ensure that the environment variables (e.g. `${postgres_host}`, `${postgres_user}`) used in `inventory-postgres-connector.json` are properly substituted or configured in your deployment environment.
- The configuration uses SMT (`ExtractField$Key`) to extract `partition_key` from the row to be used as the Kafka message key.
- A secondary SMT (`ExtractField$Value`) is used to extract `payload` from the row to be used as the Kafka message value, which contains the serialized CloudEvent.
