# Analytics Service & Pipeline Design

## 1. Overview
As per our recent architectural improvements for handling scale, the Analytics Service collects user interactions (clicks, searches, adds-to-cart, orders) to empower recommendations, metrics dashboards, and business insights.

## 2. Technology Stack
- **Ingestion / Transport**: Apache Kafka (High throughput, fault-tolerant message queue).
- **Stream Processing**: Apache Flink (Stateful transformations, handling late-arriving events, sliding window aggregations).
- **Data Warehousing / OLAP**: Apache Doris (Massively parallel processing (MPP) analytical database for sub-second dashboard query latency).

## 3. Core Pipeline
1. **Source**: Frontend Tracking SDK & Backend Service Events push to Apache Kafka topics (`analytics.clickstream`, `analytics.orders`).
2. **Flink Streaming ETL**:
   - Cleans incomplete payloads.
   - Joins streams (e.g., joining impressions with clicks).
   - Handles multi-tenancy and out-of-order data using Flink watermarks.
3. **Sink (Doris)**: Flink flushes structured wide-tables and pre-aggregated cubes to Apache Doris via the Flink-Doris connector for low-latency querying.

## 4. Key Metrics Supported
- Real-time Top Selling SKUs.
- Funnel conversion rates (View -> Cart -> Buy).
- Seller dashboard real-time statistics.
