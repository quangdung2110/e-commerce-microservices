# Cart Service - Lựa Chọn Ngôn Ngữ Lập Trình

## Quyết Định: ✅ Golang (Gin)

---

## 1. Tóm Tắt Đặc Tính Service

| Đặc tính | Mô tả |
|:---|:---|
| **Kiến trúc dữ liệu** | Redis-First — Redis là Source of Truth, PostgreSQL là Cold Store phụ |
| **Mô hình I/O** | Write-heavy nhưng rải rác, Read cực nhẹ (HGETALL), yêu cầu latency < 10ms |
| **Concurrency** | Lua Scripts trên Redis đảm bảo atomicity; không có OOP pattern phức tạp |
| **Background Workers** | Goroutines chạy Outbox Relay, Stream Trimmer, DLQ Monitor |
| **Kafka** | Producer (Outbox Relay) + Consumer (Order Event, Product Snapshot Sync) |
| **Database** | PostgreSQL chỉ làm Cold Store — logic ghi đơn giản (Upsert/Delete) |
| **Logic nghiệp vụ** | Đơn giản — CRUD giỏ hàng, batch delete |

---

## 2. Lý Do Chọn Golang

### 2.1. Goroutines — Sinh Ra Để Làm Worker
Thiết kế Cart Service phụ thuộc nặng vào **Background Workers chạy liên tục** (Outbox Relay, Stream Trimmer, DLQ Monitor, Kafka Consumers). Goroutines của Go là lựa chọn hoàn hảo:
- Khởi tạo hàng nghìn Goroutines mà chỉ tốn vài KB RAM mỗi cái (so với Thread Java tốn ~1MB/thread).
- Đã được minh chứng trong tài liệu: Worker dùng `XREADGROUP BLOCK`, `XAUTOCLAIM` — pattern chuẩn của Go Redis client.
- `sync.WaitGroup` và channels giúp điều phối worker lifecycle cực kỳ gọn gàng.

### 2.2. Latency Cực Thấp
Cart API yêu cầu response trong **1-3ms** (Lua Script trên Redis). Go biên dịch thẳng ra Binary, không có JVM Warm-up hay GC Pause:
- Cold start nhanh (< 100ms) — quan trọng cho Kubernetes Pod Scaling.
- GC pause gần như bằng 0 cho workload kiểu Cart (small heap, short-lived objects).

### 2.3. Logic Nghiệp Vụ Đơn Giản
Cart không cần OOP phức tạp:
- Không có Rule Engine, State Machine, hay Composite Pattern.
- Mọi logic nặng đã được Lua Script xử lý trên Redis.
- Go struct + interface đủ để mô hình hóa Cart Item, Event, Worker.

### 2.4. Memory Footprint Nhỏ
Cart Service có thể chạy nhiều Pod nhỏ (50-100MB RAM) để scale horizontally — phù hợp với mô hình Flash Sale cần HPA scale nhanh.

---

## 3. Tại Sao Không Chọn Java (Spring Boot)

| Hạn chế | Ảnh hưởng |
|:---|:---|
| **JVM Cold Start chậm** (3-10 giây) | Kubernetes HPA scale Pod mới phải chờ lâu — Cart cần scale nhanh trong Flash Sale |
| **Memory overhead cao** (~300-500MB/pod) | Cart Service logic đơn giản, không cần JVM heap lớn |
| **Thread model nặng** | Background Workers cần chạy liên tục — Thread Pool Java tốn RAM hơn Goroutines |
| **Không cần Spring ecosystem** | Không dùng `@Transactional`, State Machine, Caffeine L1, hay OOP Patterns phức tạp |

---

## 4. Tech Stack Chi Tiết

| Component | Công nghệ |
|:---|:---|
| **Framework** | Gin (HTTP) + gRPC (Internal) |
| **Redis Client** | `go-redis/redis` (Hỗ trợ Lua Script, Streams, Pipeline) |
| **Kafka Client** | `confluent-kafka-go` hoặc `segmentio/kafka-go` |
| **PostgreSQL** | `pgx` (Native driver, connection pooling) |
| **Logging** | `zap` (Uber's structured logger — zero allocation) |
| **Config** | `viper` |
| **Container** | Docker + scratch/distroless base image (~10MB) |
