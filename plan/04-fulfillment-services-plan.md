# Implementation Plan: Fulfillment Services Group

> **Services**: Inventory Service, Shipping Service, Payment Service, Refund Service  
> **Priority**: ⭐⭐⭐⭐⭐ CRITICAL (Order execution)  
> **Estimated Time**: 5-6 weeks  
> **Dependencies**: Shopping Services (Order), Identity Services, Common module

---

## 📋 Overview

This group handles post-order fulfillment: inventory management across warehouses, shipping logistics, payment processing with multiple gateways, and refund workflows.

### Key Challenges
1. **Multi-Warehouse Inventory**: Stock allocation across multiple locations
2. **Inventory Reservations**: Time-bound reservations with TTL (15 min default)
3. **Payment Gateway Abstraction**: Support VNPay, MoMo, Stripe, Zalopay
4. **Saga Compensation**: Refund on order cancellation
5. **Shipping Carrier Integration**: GHN, GHTK, Viettel Post APIs
6. **Partial Refunds**: Item-level refund calculation

---

## Architecture Decisions

### 1. Inventory Service - Optimistic Locking + Reservations

```
┌────────────────────────────────────────────┐
│  Stock Management                          │
│  - Available Stock                         │
│  - Reserved Stock (TTL: 15 min)           │
│  - Committed Stock (order paid)           │
│  - Version-based concurrency control      │
└────────────────────────────────────────────┘
```

**Formula**:
```
Available Stock = Total Stock - Reserved Stock - Committed Stock
```

### 2. Payment Service - Gateway Strategy Pattern

```java
public interface PaymentGateway {
    PaymentResult createPayment(PaymentRequest request);
    PaymentStatus queryPayment(String transactionId);
    RefundResult refund(String transactionId, BigDecimal amount);
}

// Implementations: VNPayGateway, MoMoGateway, StripeGateway
```

### 3. Shipping Service - Carrier Abstraction

```java
public interface ShippingCarrier {
    CreateShipmentResponse createShipment(ShipmentRequest request);
    TrackingInfo getTracking(String trackingNumber);
    BigDecimal calculateFee(ShippingFeeRequest request);
    String cancelShipment(String shipmentId);
}
```

---

## Phase 1: Inventory Service (Week 1-2)

### 1.1 Database Schema (Agent Task: DB-INV-001)

**Tables**: `warehouses`, `inventory_locations`, `inventory_reservations`, `stock_movements`

**Key Schema**:
```sql
CREATE TABLE warehouses (
    warehouse_id UUID PRIMARY KEY,
    warehouse_code VARCHAR(20) NOT NULL UNIQUE,
    name VARCHAR(200) NOT NULL,
    address JSONB NOT NULL,
    latitude DECIMAL(10, 8),
    longitude DECIMAL(11, 8),
    contact_phone VARCHAR(20),
    is_active BOOLEAN NOT NULL DEFAULT true,
    capacity_cbm DECIMAL(10, 2),
    used_capacity_cbm DECIMAL(10, 2) NOT NULL DEFAULT 0,
    manager_id UUID,
    type VARCHAR(30) NOT NULL,  -- MAIN, REGIONAL, FULFILLMENT
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE inventory_locations (
    location_id UUID PRIMARY KEY,
    warehouse_id UUID NOT NULL REFERENCES warehouses(warehouse_id),
    product_id UUID NOT NULL,
    sku_id UUID NOT NULL,
    total_stock INTEGER NOT NULL DEFAULT 0,
    available_stock INTEGER NOT NULL DEFAULT 0,
    reserved_stock INTEGER NOT NULL DEFAULT 0,
    committed_stock INTEGER NOT NULL DEFAULT 0,
    defective_stock INTEGER NOT NULL DEFAULT 0,
    min_stock_level INTEGER NOT NULL DEFAULT 0,
    max_stock_level INTEGER,
    reorder_point INTEGER NOT NULL DEFAULT 0,
    reorder_quantity INTEGER NOT NULL DEFAULT 0,
    last_restocked_at TIMESTAMPTZ,
    version INTEGER NOT NULL DEFAULT 1,
    UNIQUE(warehouse_id, sku_id)
);

CREATE INDEX idx_inv_loc_sku ON inventory_locations(sku_id);
CREATE INDEX idx_inv_loc_warehouse ON inventory_locations(warehouse_id);

CREATE TABLE inventory_reservations (
    reservation_id UUID PRIMARY KEY,
    location_id UUID NOT NULL REFERENCES inventory_locations(location_id),
    order_id UUID NOT NULL,
    sku_id UUID NOT NULL,
    quantity INTEGER NOT NULL,
    reserved_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE, CONFIRMED, EXPIRED, RELEASED
    confirmed_at TIMESTAMPTZ,
    released_at TIMESTAMPTZ,
    version INTEGER NOT NULL DEFAULT 1
);

CREATE INDEX idx_inv_res_order ON inventory_reservations(order_id);
CREATE INDEX idx_inv_res_expires ON inventory_reservations(expires_at) 
WHERE status = 'ACTIVE';

CREATE TABLE stock_movements (
    movement_id UUID PRIMARY KEY,
    location_id UUID NOT NULL REFERENCES inventory_locations(location_id),
    movement_type VARCHAR(30) NOT NULL,  -- IN, OUT, TRANSFER, ADJUST, RESERVE, RELEASE
    quantity INTEGER NOT NULL,
    previous_stock INTEGER NOT NULL,
    new_stock INTEGER NOT NULL,
    reference_type VARCHAR(50),  -- ORDER, PURCHASE_ORDER, ADJUSTMENT
    reference_id UUID,
    reason TEXT,
    performed_by UUID,
    performed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

### 1.2 Inventory Aggregate (Agent Task: INV-AGG-001)

**Commands & Events**:
```java
// Commands
public class ReserveStockCommand {
    UUID orderId;
    List<StockReservationItem> items;  // skuId, quantity, warehouseId
}

public class ConfirmReservationCommand {
    UUID reservationId;
}

public class ReleaseReservationCommand {
    UUID reservationId;
    String reason;
}

// Events
@DomainEvent
public class StockReservedEvent extends BaseEvent {
    UUID reservationId;
    UUID orderId;
    List<ReservedItem> items;
}

@DomainEvent
public class ReservationConfirmedEvent extends BaseEvent {
    UUID reservationId;
}

@DomainEvent
public class ReservationReleasedEvent extends BaseEvent {
    UUID reservationId;
    String reason;
}

@DomainEvent
public class StockLevelLowEvent extends BaseEvent {
    UUID locationId;
    UUID skuId;
    int currentStock;
    int reorderPoint;
}
```

**Aggregate Logic**:
```java
@Entity
@Data
@SuperBuilder
public class InventoryLocation {
    @Id
    private UUID locationId;
    
    @Version
    private Integer version;  // Optimistic locking
    
    private UUID warehouseId;
    private UUID skuId;
    private int totalStock;
    private int availableStock;
    private int reservedStock;
    private int committedStock;
    
    public void reserveStock(int quantity, UUID reservationId, UUID orderId) {
        if (availableStock < quantity) {
            throw new InsufficientStockException(
                String.format("Only %d available, requested %d", availableStock, quantity)
            );
        }
        
        this.availableStock -= quantity;
        this.reservedStock += quantity;
        
        // Create reservation record
        InventoryReservation reservation = InventoryReservation.builder()
            .reservationId(reservationId)
            .locationId(locationId)
            .orderId(orderId)
            .skuId(skuId)
            .quantity(quantity)
            .expiresAt(Instant.now().plus(15, ChronoUnit.MINUTES))
            .status(ReservationStatus.ACTIVE)
            .build();
        
        apply(StockReservedEvent.builder()
            .reservationId(reservationId)
            .orderId(orderId)
            .skuId(skuId.toString())
            .quantity(quantity)
            .build());
        
        // Check if below reorder point
        if (availableStock <= reorderPoint) {
            apply(StockLevelLowEvent.builder()
                .locationId(locationId.toString())
                .skuId(skuId.toString())
                .currentStock(availableStock)
                .reorderPoint(reorderPoint)
                .build());
        }
    }
    
    public void confirmReservation(UUID reservationId, int quantity) {
        this.reservedStock -= quantity;
        this.committedStock += quantity;
        
        apply(ReservationConfirmedEvent.builder()
            .reservationId(reservationId.toString())
            .build());
    }
    
    public void releaseReservation(UUID reservationId, int quantity, String reason) {
        this.reservedStock -= quantity;
        this.availableStock += quantity;
        
        apply(ReservationReleasedEvent.builder()
            .reservationId(reservationId.toString())
            .reason(reason)
            .build());
    }
    
    public void commitStock(int quantity) {
        this.committedStock -= quantity;
        this.totalStock -= quantity;
        
        apply(StockCommittedEvent.builder()
            .locationId(locationId.toString())
            .quantity(quantity)
            .build());
    }
}
```

### 1.3 Inventory Service (Agent Task: INV-SERVICE-001)

**Core Methods**:
```java
@Service
@RequiredArgsConstructor
@Transactional
public class InventoryService {
    private final InventoryLocationRepository locationRepository;
    private final InventoryReservationRepository reservationRepository;
    private final StockMovementRepository movementRepository;
    private final InventoryEventPublisher eventPublisher;
    private final RedisService redisService;
    
    public ReserveStockResponse reserveStock(ReserveStockRequest request) {
        List<InventoryReservation> reservations = new ArrayList<>();
        
        for (StockReservationItem item : request.getItems()) {
            // Find location with available stock
            InventoryLocation location = locationRepository
                .findBySkuIdWithAvailableStock(item.getSkuId(), item.getQuantity())
                .orElseThrow(() -> new InsufficientStockException(item.getSkuId()));
            
            // Reserve with optimistic lock
            UUID reservationId = UUID.randomUUID();
            try {
                location.reserveStock(item.getQuantity(), reservationId, request.getOrderId());
                locationRepository.save(location);
                
                reservations.add(InventoryReservation.builder()
                    .reservationId(reservationId)
                    .locationId(location.getLocationId())
                    .orderId(request.getOrderId())
                    .skuId(item.getSkuId())
                    .quantity(item.getQuantity())
                    .expiresAt(Instant.now().plus(15, ChronoUnit.MINUTES))
                    .status(ReservationStatus.ACTIVE)
                    .build());
                
            } catch (OptimisticLockException e) {
                // Retry with another location
                throw new StockReservationConflictException("Concurrent modification, retry");
            }
        }
        
        reservationRepository.saveAll(reservations);
        
        // Cache reservation for quick lookup
        cacheReservation(request.getOrderId(), reservations);
        
        return ReserveStockResponse.success(reservations);
    }
    
    public void confirmReservation(UUID orderId) {
        List<InventoryReservation> reservations = reservationRepository
            .findByOrderIdAndStatus(orderId, ReservationStatus.ACTIVE);
        
        for (InventoryReservation reservation : reservations) {
            InventoryLocation location = locationRepository
                .findById(reservation.getLocationId())
                .orElseThrow();
            
            location.confirmReservation(reservation.getReservationId(), reservation.getQuantity());
            locationRepository.save(location);
            
            reservation.setStatus(ReservationStatus.CONFIRMED);
            reservation.setConfirmedAt(Instant.now());
            reservationRepository.save(reservation);
            
            // Record movement
            recordStockMovement(location, MovementType.RESERVE_CONFIRM, 
                reservation.getQuantity(), "ORDER", orderId);
        }
        
        invalidateReservationCache(orderId);
    }
    
    public void releaseReservation(UUID orderId, String reason) {
        List<InventoryReservation> reservations = reservationRepository
            .findByOrderIdAndStatus(orderId, ReservationStatus.ACTIVE);
        
        for (InventoryReservation reservation : reservations) {
            InventoryLocation location = locationRepository
                .findById(reservation.getLocationId())
                .orElseThrow();
            
            location.releaseReservation(
                reservation.getReservationId(), 
                reservation.getQuantity(), 
                reason
            );
            locationRepository.save(location);
            
            reservation.setStatus(ReservationStatus.RELEASED);
            reservation.setReleasedAt(Instant.now());
            reservationRepository.save(reservation);
            
            // Record movement
            recordStockMovement(location, MovementType.RESERVE_RELEASE, 
                reservation.getQuantity(), "ORDER_CANCEL", orderId);
        }
        
        invalidateReservationCache(orderId);
    }
    
    @Scheduled(fixedRate = 60000)  // Every 1 minute
    public void expireStaleReservations() {
        List<InventoryReservation> expired = reservationRepository
            .findExpiredReservations(Instant.now());
        
        for (InventoryReservation reservation : expired) {
            releaseReservation(reservation.getOrderId(), "Expired");
        }
    }
}
```

### 1.4 Inventory API (Agent Task: INV-API-001)

**Endpoints**:
```java
POST   /api/inventory/reserve         - Reserve stock
POST   /api/inventory/confirm          - Confirm reservation
POST   /api/inventory/release          - Release reservation
GET    /api/inventory/stock/{skuId}    - Get stock levels
GET    /api/inventory/movements        - Stock movement history

// Admin endpoints
POST   /api/admin/inventory/adjust     - Adjust stock levels
POST   /api/admin/inventory/transfer   - Transfer between warehouses
GET    /api/admin/inventory/low-stock  - Low stock alerts
```

---

## Phase 2: Shipping Service (Week 3)

### 2.1 Database Schema (Agent Task: DB-SHIP-001)

**Tables**: `shipments`, `shipment_items`, `shipment_tracking_events`, `shipping_carriers`

**Schema**:
```sql
CREATE TABLE shipments (
    shipment_id UUID PRIMARY KEY,
    order_id UUID NOT NULL,
    tracking_number VARCHAR(50) NOT NULL UNIQUE,
    carrier_code VARCHAR(30) NOT NULL,  -- GHN, GHTK, VIETTEL_POST
    carrier_shipment_id VARCHAR(100),
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    -- Addresses
    sender_address JSONB NOT NULL,
    receiver_address JSONB NOT NULL,
    receiver_name VARCHAR(200) NOT NULL,
    receiver_phone VARCHAR(20) NOT NULL,
    -- Package details
    weight_grams INTEGER NOT NULL,
    length_cm INTEGER,
    width_cm INTEGER,
    height_cm INTEGER,
    insurance_value DECIMAL(15, 2),
    cod_amount DECIMAL(15, 2),
    -- Fees
    shipping_fee DECIMAL(15, 2) NOT NULL,
    insurance_fee DECIMAL(15, 2) NOT NULL DEFAULT 0,
    cod_fee DECIMAL(15, 2) NOT NULL DEFAULT 0,
    total_fee DECIMAL(15, 2) NOT NULL,
    -- Tracking
    estimated_delivery_date DATE,
    actual_delivery_date DATE,
    current_location VARCHAR(200),
    notes TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    picked_up_at TIMESTAMPTZ,
    in_transit_at TIMESTAMPTZ,
    delivered_at TIMESTAMPTZ,
    failed_at TIMESTAMPTZ,
    returned_at TIMESTAMPTZ,
    version INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE shipment_tracking_events (
    event_id UUID PRIMARY KEY,
    shipment_id UUID NOT NULL REFERENCES shipments(shipment_id),
    status VARCHAR(30) NOT NULL,
    location VARCHAR(200),
    description TEXT,
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_tracking_events_shipment ON shipment_tracking_events(shipment_id, occurred_at DESC);
```

### 2.2 Shipping Carrier Abstraction (Agent Task: SHIP-CARRIER-001)

**Interface**:
```java
public interface ShippingCarrier {
    String getCarrierCode();
    
    CreateShipmentResponse createShipment(ShipmentRequest request);
    
    TrackingInfo getTracking(String trackingNumber);
    
    BigDecimal calculateFee(ShippingFeeRequest request);
    
    CancelShipmentResponse cancelShipment(String carrierShipmentId);
    
    UpdateShipmentResponse updateShipment(String carrierShipmentId, UpdateShipmentRequest request);
}
```

**GHN Implementation**:
```java
@Component
@RequiredArgsConstructor
public class GHNCarrier implements ShippingCarrier {
    private final RestTemplate restTemplate;
    
    @Value("${shipping.ghn.api-url}")
    private String apiUrl;
    
    @Value("${shipping.ghn.token}")
    private String token;
    
    @Override
    public String getCarrierCode() {
        return "GHN";
    }
    
    @Override
    public CreateShipmentResponse createShipment(ShipmentRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Token", token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        
        GHNCreateShipmentRequest ghnRequest = GHNCreateShipmentRequest.builder()
            .toName(request.getReceiverName())
            .toPhone(request.getReceiverPhone())
            .toAddress(request.getReceiverAddress().getFullAddress())
            .toWardCode(request.getReceiverAddress().getWardCode())
            .toDistrictId(request.getReceiverAddress().getDistrictId())
            .codAmount(request.getCodAmount())
            .weight(request.getWeightGrams())
            .length(request.getLengthCm())
            .width(request.getWidthCm())
            .height(request.getHeightCm())
            .serviceTypeId(2)  // Standard delivery
            .paymentTypeId(1)  // Seller pays
            .requiredNote("CHOXEMHANGKHONGTHU")
            .items(mapItems(request.getItems()))
            .build();
        
        HttpEntity<GHNCreateShipmentRequest> entity = new HttpEntity<>(ghnRequest, headers);
        
        ResponseEntity<GHNCreateShipmentResponse> response = restTemplate.postForEntity(
            apiUrl + "/v2/shipping-order/create",
            entity,
            GHNCreateShipmentResponse.class
        );
        
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new ShippingCarrierException("Failed to create GHN shipment");
        }
        
        GHNCreateShipmentResponse ghnResponse = response.getBody();
        
        return CreateShipmentResponse.builder()
            .carrierShipmentId(ghnResponse.getData().getOrderCode())
            .trackingNumber(ghnResponse.getData().getOrderCode())
            .estimatedDeliveryDate(ghnResponse.getData().getExpectedDeliveryTime())
            .shippingFee(ghnResponse.getData().getTotalFee())
            .build();
    }
    
    @Override
    public TrackingInfo getTracking(String trackingNumber) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Token", token);
        
        HttpEntity<?> entity = new HttpEntity<>(headers);
        
        ResponseEntity<GHNTrackingResponse> response = restTemplate.exchange(
            apiUrl + "/v2/shipping-order/detail?order_code=" + trackingNumber,
            HttpMethod.GET,
            entity,
            GHNTrackingResponse.class
        );
        
        // Map GHN status to internal status
        GHNTrackingResponse.Data data = response.getBody().getData();
        
        return TrackingInfo.builder()
            .trackingNumber(trackingNumber)
            .status(mapGHNStatus(data.getStatus()))
            .currentLocation(data.getCurrentLocation())
            .estimatedDeliveryDate(data.getExpectedDeliveryTime())
            .events(mapTrackingEvents(data.getLogs()))
            .build();
    }
    
    @Override
    public BigDecimal calculateFee(ShippingFeeRequest request) {
        // Call GHN fee calculation API
        HttpHeaders headers = new HttpHeaders();
        headers.set("Token", token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        
        GHNFeeRequest feeRequest = GHNFeeRequest.builder()
            .serviceTypeId(2)
            .toDistrictId(request.getToDistrictId())
            .toWardCode(request.getToWardCode())
            .weight(request.getWeightGrams())
            .length(request.getLengthCm())
            .width(request.getWidthCm())
            .height(request.getHeightCm())
            .insuranceValue(request.getInsuranceValue())
            .codAmount(request.getCodAmount())
            .build();
        
        HttpEntity<GHNFeeRequest> entity = new HttpEntity<>(feeRequest, headers);
        
        ResponseEntity<GHNFeeResponse> response = restTemplate.postForEntity(
            apiUrl + "/v2/shipping-order/fee",
            entity,
            GHNFeeResponse.class
        );
        
        return response.getBody().getData().getTotalFee();
    }
    
    private ShipmentStatus mapGHNStatus(String ghnStatus) {
        return switch (ghnStatus) {
            case "ready_to_pick" -> ShipmentStatus.PENDING;
            case "picking" -> ShipmentStatus.PICKING_UP;
            case "money_collect_picking" -> ShipmentStatus.PICKING_UP;
            case "picked" -> ShipmentStatus.PICKED_UP;
            case "storing" -> ShipmentStatus.IN_TRANSIT;
            case "transporting" -> ShipmentStatus.IN_TRANSIT;
            case "sorting" -> ShipmentStatus.IN_TRANSIT;
            case "delivering" -> ShipmentStatus.OUT_FOR_DELIVERY;
            case "delivered" -> ShipmentStatus.DELIVERED;
            case "return" -> ShipmentStatus.RETURNING;
            case "returned" -> ShipmentStatus.RETURNED;
            case "exception" -> ShipmentStatus.FAILED;
            default -> ShipmentStatus.UNKNOWN;
        };
    }
}
```

### 2.3 Shipping Service (Agent Task: SHIP-SERVICE-001)

**Core Logic**:
```java
@Service
@RequiredArgsConstructor
public class ShippingService {
    private final ShipmentRepository shipmentRepository;
    private final Map<String, ShippingCarrier> carriers;  // Injected via constructor
    private final ShippingEventPublisher eventPublisher;
    
    public CreateShipmentResponse createShipment(CreateShipmentRequest request) {
        // Select carrier (default or specified)
        ShippingCarrier carrier = selectCarrier(request.getCarrierCode());
        
        // Create shipment with carrier
        CreateShipmentResponse carrierResponse = carrier.createShipment(
            mapToCarrierRequest(request)
        );
        
        // Save shipment record
        Shipment shipment = Shipment.builder()
            .shipmentId(UUID.randomUUID())
            .orderId(request.getOrderId())
            .trackingNumber(carrierResponse.getTrackingNumber())
            .carrierCode(carrier.getCarrierCode())
            .carrierShipmentId(carrierResponse.getCarrierShipmentId())
            .status(ShipmentStatus.PENDING)
            .senderAddress(toJson(request.getSenderAddress()))
            .receiverAddress(toJson(request.getReceiverAddress()))
            .receiverName(request.getReceiverName())
            .receiverPhone(request.getReceiverPhone())
            .weightGrams(request.getWeightGrams())
            .shippingFee(carrierResponse.getShippingFee())
            .totalFee(carrierResponse.getShippingFee())
            .estimatedDeliveryDate(carrierResponse.getEstimatedDeliveryDate())
            .createdAt(Instant.now())
            .build();
        
        shipmentRepository.save(shipment);
        
        // Publish event
        eventPublisher.publish(ShipmentCreatedEvent.builder()
            .shipmentId(shipment.getShipmentId().toString())
            .orderId(request.getOrderId().toString())
            .trackingNumber(shipment.getTrackingNumber())
            .carrierCode(carrier.getCarrierCode())
            .build());
        
        return carrierResponse;
    }
    
    @Async
    @Scheduled(fixedRate = 300000)  // Every 5 minutes
    public void syncTrackingUpdates() {
        List<Shipment> activeShipments = shipmentRepository.findByStatusIn(
            List.of(ShipmentStatus.PENDING, ShipmentStatus.IN_TRANSIT, ShipmentStatus.OUT_FOR_DELIVERY)
        );
        
        for (Shipment shipment : activeShipments) {
            try {
                ShippingCarrier carrier = carriers.get(shipment.getCarrierCode());
                TrackingInfo tracking = carrier.getTracking(shipment.getTrackingNumber());
                
                // Update status if changed
                if (tracking.getStatus() != shipment.getStatus()) {
                    updateShipmentStatus(shipment, tracking);
                }
                
                // Save tracking events
                saveTrackingEvents(shipment, tracking.getEvents());
                
            } catch (Exception e) {
                log.error("Failed to sync tracking for shipment: {}", shipment.getShipmentId(), e);
            }
        }
    }
}
```

### 2.4 Shipping API (Agent Task: SHIP-API-001)

**Endpoints**:
```java
POST   /api/shipping/shipments           - Create shipment
GET    /api/shipping/shipments/{id}      - Get shipment details
GET    /api/shipping/track/{tracking}    - Track shipment
POST   /api/shipping/calculate-fee       - Calculate shipping fee
POST   /api/shipping/{id}/cancel         - Cancel shipment
```

---

## Phase 3: Payment Service (Week 4)

### 3.1 Database Schema (Agent Task: DB-PAY-001)

**Tables**: `payments`, `payment_transactions`, `refunds`

**Schema**:
```sql
CREATE TABLE payments (
    payment_id UUID PRIMARY KEY,
    order_id UUID NOT NULL UNIQUE,
    amount DECIMAL(15, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'VND',
    payment_method VARCHAR(30) NOT NULL,  -- VNPAY, MOMO, STRIPE, ZALOPAY, COD
    gateway_transaction_id VARCHAR(100),
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    gateway_response JSONB,
    paid_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    version INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE refunds (
    refund_id UUID PRIMARY KEY,
    payment_id UUID NOT NULL REFERENCES payments(payment_id),
    order_id UUID NOT NULL,
    reason VARCHAR(50) NOT NULL,  -- ORDER_CANCELLED, ITEM_RETURNED, PRICE_ADJUSTMENT
    refund_type VARCHAR(20) NOT NULL,  -- FULL, PARTIAL
    amount DECIMAL(15, 2) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    gateway_refund_id VARCHAR(100),
    refunded_at TIMESTAMPTZ,
    version INTEGER NOT NULL DEFAULT 1
);
```

### 3.2 Payment Gateway Abstraction (Agent Task: PAY-GATEWAY-001)

**VNPay Implementation**:
```java
@Component
@RequiredArgsConstructor
public class VNPayGateway implements PaymentGateway {
    
    @Value("${payment.vnpay.tmn-code}")
    private String tmnCode;
    
    @Value("${payment.vnpay.hash-secret}")
    private String hashSecret;
    
    @Value("${payment.vnpay.url}")
    private String vnpayUrl;
    
    @Override
    public PaymentResult createPayment(PaymentRequest request) {
        Map<String, String> params = new TreeMap<>();
        params.put("vnp_Version", "2.1.0");
        params.put("vnp_Command", "pay");
        params.put("vnp_TmnCode", tmnCode);
        params.put("vnp_Amount", String.valueOf(request.getAmount().multiply(BigDecimal.valueOf(100)).longValue()));
        params.put("vnp_CurrCode", "VND");
        params.put("vnp_TxnRef", request.getOrderId().toString());
        params.put("vnp_OrderInfo", "Payment for order " + request.getOrderId());
        params.put("vnp_OrderType", "other");
        params.put("vnp_Locale", "vn");
        params.put("vnp_ReturnUrl", request.getReturnUrl());
        params.put("vnp_IpAddr", request.getIpAddress());
        params.put("vnp_CreateDate", new SimpleDateFormat("yyyyMMddHHmmss").format(new Date()));
        
        // Calculate secure hash
        String signData = buildSignData(params);
        String secureHash = hmacSHA512(hashSecret, signData);
        params.put("vnp_SecureHash", secureHash);
        
        // Build payment URL
        String paymentUrl = vnpayUrl + "?" + buildQueryString(params);
        
        return PaymentResult.builder()
            .transactionId(request.getOrderId().toString())
            .paymentUrl(paymentUrl)
            .status(PaymentStatus.PENDING)
            .build();
    }
    
    @Override
    public PaymentStatus queryPayment(String transactionId) {
        // Query VNPay API for payment status
        // Implementation depends on VNPay's query endpoint
        return PaymentStatus.PENDING;
    }
    
    @Override
    public RefundResult refund(String transactionId, BigDecimal amount) {
        Map<String, String> params = new TreeMap<>();
        params.put("vnp_Version", "2.1.0");
        params.put("vnp_Command", "refund");
        params.put("vnp_TmnCode", tmnCode);
        params.put("vnp_Amount", String.valueOf(amount.multiply(BigDecimal.valueOf(100)).longValue()));
        params.put("vnp_TxnRef", transactionId);
        params.put("vnp_TransactionType", "02");  // Refund
        params.put("vnp_TransactionDate", new SimpleDateFormat("yyyyMMddHHmmss").format(new Date()));
        
        String signData = buildSignData(params);
        String secureHash = hmacSHA512(hashSecret, signData);
        params.put("vnp_SecureHash", secureHash);
        
        // Call VNPay refund API
        HttpEntity<Map<String, String>> entity = new HttpEntity<>(params);
        ResponseEntity<VNPayRefundResponse> response = restTemplate.postForEntity(
            vnpayUrl + "/refund",
            entity,
            VNPayRefundResponse.class
        );
        
        return RefundResult.builder()
            .refundId(response.getBody().getRefundId())
            .status(response.getBody().isSuccess() ? RefundStatus.COMPLETED : RefundStatus.FAILED)
            .build();
    }
    
    private String hmacSHA512(String key, String data) {
        try {
            Mac hmac = Mac.getInstance("HmacSHA512");
            SecretKeySpec secretKey = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA512");
            hmac.init(secretKey);
            byte[] hash = hmac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to calculate HMAC", e);
        }
    }
}
```

### 3.3 Payment Service (Agent Task: PAY-SERVICE-001)

**Temporal Activities**:
```java
@Component
@RequiredArgsConstructor
public class PaymentActivitiesImpl implements PaymentActivities {
    private final PaymentService paymentService;
    
    @Override
    public PaymentResult processPayment(UUID orderId, BigDecimal amount, String paymentMethod) {
        return paymentService.processPayment(orderId, amount, paymentMethod);
    }
    
    @Override
    public RefundResult refundPayment(UUID paymentId) {
        return paymentService.refund(paymentId, "Order cancelled");
    }
}
```

---

## Phase 4: Refund Service (Week 5)

### 4.1 Refund Workflow (Agent Task: REFUND-WORKFLOW-001)

**Refund Types**:
```java
public enum RefundReason {
    ORDER_CANCELLED,      // Full refund before shipping
    ITEM_RETURNED,        // Partial/full refund after delivery
    PRICE_ADJUSTMENT,     // Partial refund for price errors
    ITEM_DAMAGED,         // Full refund with photo evidence
    NEVER_RECEIVED        // Full refund if shipment failed
}
```

**Refund Calculator**:
```java
@Service
@RequiredArgsConstructor
public class RefundCalculator {
    
    public RefundCalculation calculate(Order order, RefundReason reason, List<UUID> itemIds) {
        BigDecimal refundAmount = BigDecimal.ZERO;
        
        switch (reason) {
            case ORDER_CANCELLED:
                // Full refund
                refundAmount = order.getTotalAmount();
                break;
                
            case ITEM_RETURNED:
                // Partial refund for specific items
                List<OrderItem> returnedItems = order.getItems().stream()
                    .filter(item -> itemIds.contains(item.getOrderItemId()))
                    .toList();
                
                refundAmount = returnedItems.stream()
                    .map(OrderItem::getSubtotal)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                
                // Prorate shipping if all items returned
                if (returnedItems.size() == order.getItems().size()) {
                    refundAmount = refundAmount.add(order.getShippingFee());
                }
                break;
                
            case PRICE_ADJUSTMENT:
                // Calculate difference
                refundAmount = calculatePriceDifference(order, itemIds);
                break;
        }
        
        return RefundCalculation.builder()
            .refundAmount(refundAmount)
            .reason(reason)
            .itemIds(itemIds)
            .build();
    }
}
```

---

## AI Agent Task Breakdown

### Week 1-2: Inventory Service
| Task ID | Description | Time | Dependencies |
|---------|-------------|------|--------------|
| DB-INV-001 | Database schema | 6h | - |
| INV-AGG-001 | Inventory aggregate | 10h | DB-INV-001 |
| INV-SERVICE-001 | Service layer | 14h | INV-AGG-001 |
| INV-API-001 | REST API | 8h | INV-SERVICE-001 |

### Week 3: Shipping Service
| Task ID | Description | Time | Dependencies |
|---------|-------------|------|--------------|
| DB-SHIP-001 | Database schema | 4h | - |
| SHIP-CARRIER-001 | Carrier abstraction | 12h | DB-SHIP-001 |
| SHIP-SERVICE-001 | Service layer | 10h | SHIP-CARRIER-001 |
| SHIP-API-001 | REST API | 6h | SHIP-SERVICE-001 |

### Week 4: Payment Service
| Task ID | Description | Time | Dependencies |
|---------|-------------|------|--------------|
| DB-PAY-001 | Database schema | 4h | - |
| PAY-GATEWAY-001 | Gateway abstraction | 12h | DB-PAY-001 |
| PAY-SERVICE-001 | Service layer | 10h | PAY-GATEWAY-001 |
| PAY-API-001 | REST API | 6h | PAY-SERVICE-001 |

### Week 5-6: Integration & Testing
| Task ID | Description | Time | Dependencies |
|---------|-------------|------|--------------|
| REFUND-WORKFLOW-001 | Refund workflow | 10h | PAY-SERVICE-001 |
| INT-FULFILL-001 | Event integration | 8h | All services |
| TEST-FULFILL-E2E-001 | E2E tests | 16h | All services |

---

## Validation Checklist

### Inventory Service
- [ ] Optimistic locking prevents overselling
- [ ] Reservations expire after 15 min
- [ ] Low stock alerts triggered
- [ ] Multi-warehouse allocation works
- [ ] Stock movements recorded

### Shipping Service
- [ ] Multiple carriers supported (GHN, GHTK)
- [ ] Fee calculation accurate
- [ ] Tracking syncs every 5 min
- [ ] Webhook handlers implemented
- [ ] Shipment cancellation works

### Payment Service
- [ ] VNPay integration functional
- [ ] MoMo integration functional
- [ ] Secure hash validation passes
- [ ] Payment timeout handled
- [ ] Refunds processed successfully

### Integration
- [ ] Order saga triggers inventory reservation
- [ ] Payment success confirms reservation
- [ ] Order cancellation releases inventory
- [ ] Shipment events update order status
- [ ] Refund workflow completes end-to-end

**End of Fulfillment Services Implementation Plan**
