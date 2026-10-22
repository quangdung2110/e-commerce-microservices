# Service Mesh — Architecture Decision Records

Quyết định công nghệ ban đầu nằm ở [technology_choice.md](../technology_choice.md); thư mục này ghi các quyết định về cách triển khai.
Hành vi vận hành bắt buộc vẫn nằm ở [runtime-contract.md](../runtime-contract.md).

| ADR | Decision | Status | Date |
|---|---|---|---|
| [ADR-001](./ADR-001-manifests-generated-from-runtime-contract.md) | Manifest mesh sinh từ runtime contract bằng `deploy/mesh/generate.py`; chốt Istio 1.24.2; dịch đường dẫn sang Istio; kiểm tra không cần cluster | Accepted | 2026-10-16 |
| [ADR-002](./ADR-002-local-runtime-exposure-and-broker-auth.md) | Ngoài mesh: chỉ gateway/frontend nghe mọi interface (compose loopback, k8s ClusterIP + NetworkPolicy), Redis `requirepass`, Kafka tắt auto-create; định danh ký là việc còn mở (SASL/ACL: ADR-003) | Accepted | 2026-10-22 |
| [ADR-003](./ADR-003-kafka-sasl-scram-and-acls.md) | Kafka SASL/SCRAM-SHA-512 một principal mỗi client (kể cả từng connector Debezium), ACL theo `infra/kafka/acls.txt` (WRITE chỉ chủ topic), `kafka-init` đồng bộ user/ACL bằng Admin API, ZooKeeper chỉ broker tới được | Accepted | 2026-10-22 |
