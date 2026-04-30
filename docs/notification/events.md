# Notification Service CloudEvents Schema

Notification Service tiếp nhận các **Fat Events** từ các core services khác. Chuẩn chung là CloudEvents. Các Kafka Payload này chứa đầy đủ thông tin để render notification content mà không cần gọi Request/Reply qua các dịch vụ khác.

## 1. High-Priority Events

Sử dụng topic: `notification.high-priority.events`

### 1.1 User OTP/MFA Requested (Từ Auth Service)
```json
{
  "specversion": "1.0",
  "id": "e44c20b4-419b-43af-b74c-471249b6ab50",
  "source": "/auth-service/mfa",
  "type": "USER_OTP_REQUESTED",
  "time": "2026-04-27T10:00:00Z",
  "datacontenttype": "application/json",
  "data": {
    "user_id": "u-12345",
    "email": "user@example.com",
    "otp_code": "489213",
    "action": "LOGIN",
    "device_info": "Chrome on Macbook - IP: 192.168.1.1",
    "expires_in_minutes": 5
  }
}
```

## 2. Low-Priority (Standard) Events

Sử dụng topic: `notification.low-priority.events`

### 2.1 Order Created (Từ Order Service)
Gửi thông báo xác nhận đơn hàng thành công. Fat Event chứa đủ tên sản phẩm và tổng tiền.

```json
{
  "specversion": "1.0",
  "id": "ord-event-999201",
  "source": "/order-service/orders",
  "type": "ORDER_CREATED",
  "time": "2026-04-27T14:30:15Z",
  "datacontenttype": "application/json",
  "data": {
    "order_id": "ORD-20260427-001",
    "user_id": "u-12345",
    "customer_email": "user@example.com",
    "customer_name": "Nguyen Van A",
    "total_amount": 1500000.00,
    "currency": "VND",
    "items_summary": "iPhone 15 Pro Max và 2 sản phẩm khác",
    "payment_method": "VNPAY",
    "shipping_address": "123 Le Loi, Quận 1, TP. HCM"
  }
}
```

### 2.2 Payment Failed (Từ Payment Service)
Cảnh báo user nạp tiền/thanh toán gặp sự cố, kèm link thử lại.

```json
{
  "specversion": "1.0",
  "id": "pay-event-77382",
  "source": "/payment-service/transactions",
  "type": "PAYMENT_FAILED",
  "time": "2026-04-27T14:32:00Z",
  "datacontenttype": "application/json",
  "data": {
    "order_id": "ORD-20260427-001",
    "user_id": "u-12345",
    "amount_failed": 1500000.00,
    "reason": "INSUFFICIENT_FUNDS",
    "retry_url": "https://ecommerce.com/checkout/retry/ORD-20260427-001"
  }
}
```

### 2.3 Promotion / Marketing (Từ Promotion Service)
Event Bulk-push. Có thể chứa metadata của promotion.

```json
{
  "specversion": "1.0",
  "id": "promo-event-110022",
  "source": "/promotion-service/campaigns",
  "type": "PROMOTION_FLASH_SALE",
  "time": "2026-04-27T08:00:00Z",
  "datacontenttype": "application/json",
  "data": {
    "user_id": "u-12345",
    "campaign_name": "Siêu sale cuối tháng 4",
    "discount_percent": 30,
    "highlight_product": "Laptop Gaming",
    "valid_until": "2026-04-30T23:59:59Z",
    "action_url": "https://ecommerce.com/flash-sale"
  }
}
```

## 3. Worker Processing Strategy
Dựa vào data từ event, Worker sẽ thực thi:
1. Lookup Template theo `type` (VD: `ORDER_CREATED`).
2. Regex replace hoặc sử dụng Handlebars (hoặc Jinja2) để thay thế data từ object `data` vào chuỗi Template.
Ví dụ Template Email: `"Xin chào {{customer_name}}, cảm ơn bạn đã đặt đơn hàng {{order_id}}!"`
3. Gửi thông qua các channel theo cấu hình Preference của user_id.