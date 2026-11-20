# ADR-007: Cân bằng tải và keepalive của kênh gRPC

- Status: Accepted
- Date: 2026-10-15

## Context

gRPC chạy trên HTTP/2: một client mở một kết nối dài và dồn mọi RPC vào đó. Khảo sát 2026-10-15 cho thấy cả hai
client của `ProductService` dùng cấu hình mặc định:

| Client | Cấu hình hiện tại |
|---|---|
| Cart (Go) | `grpc.DialContext(address, WithTransportCredentials(insecure))`: scheme `passthrough`, một địa chỉ, `pick_first`, không keepalive |
| Checkout (Java) | `ManagedChannelBuilder.forTarget(target).usePlaintext()`: resolver DNS, `pick_first`, không keepalive |

[Mesh design](../../docs/mesh/design.md) giao việc chia đều theo request cho waypoint. Điều đó chỉ đúng khi có waypoint;
Docker Compose không có mesh, và một cụm Kubernetes chưa bật waypoint sẽ rơi vào:

1. **Mọi RPC của một pod Cart/Checkout vào đúng một pod Product**, chọn lúc kết nối. Thêm pod Product không giảm tải
   vì kết nối cũ vẫn sống.
2. **Kết nối chết không phát hiện được**: một NAT hay load balancer giữa chừng có thể bỏ kết nối im lặng; client
   chỉ biết sau một RPC timeout.
3. **Không có chỗ rebalance**: server không bao giờ đóng kết nối.

## Decision

1. **Client round-robin theo địa chỉ DNS.** Target dùng scheme `dns:///host:port` tường minh (Go mặc định
   `passthrough`, nên không tự có) và service config `round_robin`. Với Service headless (`clusterIP: None`),
   DNS trả mọi pod và RPC chia đều giữa chúng. Với Service ClusterIP (có waypoint) DNS chỉ trả một địa chỉ ảo nên
   `round_robin` vô hại: waypoint vẫn là nơi cân bằng.
2. **Server đóng kết nối định kỳ.** Product đặt `max-connection-age` 5 phút (grpc-java cộng ngẫu nhiên ±10%) và
   `max-connection-age-grace` 30 giây: server gửi `GOAWAY`, RPC đang chạy được hoàn tất, client kết nối lại và phân giải DNS
   lại. Đó là cách pod Product mới nhận tải mà không cần restart client.
3. **Keepalive client:** ping mỗi 30 giây khi có RPC đang chạy hoặc kênh đang hoạt động (`PermitWithoutStream`/
   `keepAliveWithoutCalls` tắt), timeout 10 giây. Kết nối không trả lời bị đóng sớm thay vì chờ deadline của RPC kế tiếp.
4. **Server cho phép keepalive đó:** `permit-keep-alive-time` 20 giây (nhỏ hơn 30 giây của client) và không cho ping
   khi không có RPC. Mặc định gRPC là 5 phút và sẽ đóng kết nối bằng `too_many_pings`.
5. **Không thêm retry ở tầng kênh.** Retry vẫn theo [ADR-003](ADR-003-deadline-propagation.md) và các ADR resilience của từng
   service; `GetSku`/`GetBatchSkus` an toàn để retry nhưng hot path không retry ở ứng dụng.
6. Giá trị là hằng số của client (Cart có thể ghi đè bằng `Config`), và cấu hình server nằm trong `application.yml` của
   Product, đổi bằng biến môi trường `GRPC_SERVER_*`.
7. Service gRPC mới theo cùng bộ tham số; ngoại lệ phải ghi lý do.

## Alternatives considered

- **Chỉ dựa vào waypoint.** Không chọn: Compose và cụm không bật waypoint mất cân bằng mà không có dấu hiệu nào.
- **Cân bằng phía server (một load balancer L7 riêng).** Không chọn: thêm một hop và một thành phần mà mesh đã được chọn
  để thay.
- **`xDS` client-side.** Không chọn: cần control plane gRPC-xDS riêng, trong khi dự án chọn Istio ambient.
- **Retry policy trong service config.** Không chọn: ADR-003 giữ việc retry ở tầng đã biết ngân sách deadline.

## Consequences

- Kết nối giữa Cart/Checkout và Product được tái lập mỗi ~5 phút (trong suốt với RPC nhờ grace).
- Triển khai Kubernetes **không có waypoint** phải dùng Service headless cho cổng gRPC của Product để `round_robin` có tác dụng;
  có waypoint thì dùng ClusterIP như [mesh runtime contract §3](../../docs/mesh/runtime-contract.md). Ghi ở mesh §11.
- Docker Compose (một container Product) hành xử như cũ.
- Pod Product hiện có không đổi ở client cũ: client chưa nâng cấp vẫn chạy được vì ping của chúng không có (không vi phạm
  `permit-keep-alive-time`).

## Verification

- Cart: `channel_test.go` — ba backend qua resolver thủ công nhận gần đều 30 lời gọi; một server đóng kết nối mỗi 200 ms
  trong khi client gọi liên tục 2 giây không lỗi nào và chấp nhận ít nhất ba kết nối; `target()` thêm `dns:///`; khoảng cách
  keepalive so với ngưỡng của Product.
- Checkout: `ProductChannelTest` — ba backend qua `NameResolverProvider` thử nghiệm nhận gần đều; hằng số keepalive.
- Product: `GrpcServerConnectionPolicyTest` đọc `application.yml` và kiểm `max-connection-age`, grace, `permit-keep-alive-time`.
- Chưa chứng minh được trên cluster có nhiều pod Product (không có cluster); phần đó nằm trong tiêu chí xác minh mesh
  ([runtime contract §10](../../docs/mesh/runtime-contract.md)).
