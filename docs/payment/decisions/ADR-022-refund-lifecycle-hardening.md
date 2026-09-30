---
doc_id: payment-adr-022
service: payment
status: accepted
date: 2026-09-30
decision_owners:
  - Payment Service team
supersedes: null
evidence:
  - docs/payment/domain.md
  - docs/payment/workflows.md
  - docs/payment/interfaces.md
  - docs/payment/data.md
  - docs/payment/operations.md
---

# ADR-022 — Củng cố vòng đời Refund theo hành vi thật của Stripe

## Trạng thái

`Accepted` — 2026-09-30. Refines ADR-003, ADR-011, ADR-018, ADR-019.

**Triển khai:** tài liệu và code `payment-service` đã theo quyết định này (2026-09-30);
xem "Đã triển khai ở code" và "Việc còn lại".

## Bối cảnh

Đối chiếu thiết kế refund với tài liệu chính thức của Stripe
([Refund and cancel payments](https://docs.stripe.com/refunds),
[Refund object](https://docs.stripe.com/api/refunds/object)) cho thấy:

- Stripe chỉ *chuyển* lệnh refund tới issuer; issuer có thể trả tiền về Stripe sau đó
  (tối đa khoảng 30 ngày), khi đó refund chuyển sang `failed`. Với thẻ hết hạn/bị hủy,
  issuer thường ghi vào thẻ thay thế, nên lỗi này hiếm nhưng có thật.
- `charge.refunded` có `data.object` là **charge**, không mang trạng thái refund; Stripe
  hướng dẫn dùng `refund.created`/`refund.updated` để biết refund. Refund về thẻ ở
  `pending` khi số dư Stripe khả dụng không đủ.
- Refund có 5 status: `pending`, `requires_action`, `succeeded`, `failed`, `canceled`.
  `canceled` là một dạng thất bại (có `failure_reason`/`failure_balance_transaction`).
- Stripe cũng dùng chữ "reversal" cho *charge reversal*: refund ngay sau charge thì
  khoản gốc biến khỏi sao kê khách (`destination_details.card.type = 'reversal'`).
- Thiết kế cũ chốt `R2` bằng `charge.refunded`, chỉ map `status=failed`, và không nêu
  phạm vi phương thức thanh toán, danh mục `failure_reason` đầy đủ hay dispute chồng refund.

## Quyết định

1. **Nguồn sự thật của trạng thái refund là Refund object.** Bảng ánh xạ chuẩn:

   | Stripe `Refund.status` | Refund nội bộ `PROCESSING` | Refund nội bộ `SUCCESS` |
   |---|---|---|
   | `pending` | Giữ; chỉ cập nhật `provider_status_snapshot` | Không xảy ra; ReviewCase `RECONCILIATION_DRIFT` |
   | `succeeded` | `R2` + `T7` | No-op |
   | `failed` | `R3` + `T8` | `R5` + `T9` |
   | `canceled` | `R3` + `T8` (`error_code = MERCHANT_REQUEST`) | `R5` + `T9` |
   | `requires_action` | Giữ; ReviewCase `RECONCILIATION_DRIFT` (`provider_status=requires_action`) | `R5` + `T9` (ngân hàng trả tiền về Stripe, khách chưa nhận) |

2. **`charge.refunded` chỉ là tín hiệu, không tự chốt `SUCCESS`.** Worker resolve payment
   qua `ch_xxx`, gọi `Refund.retrieve(provider_refund_id)` và áp bảng trên. `refund.updated`
   với `status=succeeded` cũng chốt `R2`. Vẫn subscribe `charge.refunded` (12 event không đổi).

3. **Reconciliation phải bắt được reversal đến 30 ngày.** Refund `SUCCESS` được quét lại
   trong `payment.reconciliation.refund-success-lookback` = `P35D` tính từ `finalized_at`,
   độc lập với `updated_at`; quá hạn thì thôi quét. Thứ tự quét theo `provider_synced_at`
   cũ nhất trước (NULLS FIRST) để refund mới không bị bỏ đói khi vượt `batch-size`.
   Refund `PROCESSING` luôn được quét.

4. **Phạm vi phương thức thanh toán là `card`.** Payment chỉ hỗ trợ thẻ; Stripe Dashboard
   payment-method configuration và PaymentIntent phải giới hạn ở `card`. Phương thức
   bất đồng bộ/bank debit/bank transfer cần ADR mở rộng riêng vì đường hoàn tiền thất
   bại của chúng khác (`requires_action`, rủi ro double refund khi có dispute).

5. **`failure_reason` được chuẩn hóa đủ 7 giá trị** Stripe thành machine code trong
   `REFUND_FAILED.error_code` và `provider_failure_reason`: `LOST_OR_STOLEN_CARD`,
   `EXPIRED_OR_CANCELED_CARD`, `CHARGE_FOR_PENDING_REFUND_DISPUTED`, `DECLINED`,
   `INSUFFICIENT_FUNDS`, `MERCHANT_REQUEST`, `UNKNOWN`. Contract event không đổi
   (`machine_code` đã chấp nhận các giá trị này).

6. **Ledger dùng đúng balance transaction.** Journal `REFUND` lấy `refund.balance_transaction`;
   journal `REFUND_REVERSAL` lấy `refund.failure_balance_transaction`. Không dùng
   `balance_transaction` của charge object trong event `charge.refunded` (đó là giao dịch
   capture). Nếu chưa có id, dùng fallback `source_type/source_id` theo `data.md §3.11`.

7. **Refund chồng dispute chỉ được ghi nhận, chưa bị chặn.** Khi dispute mở trên payment đã
   có refund `PROCESSING`/`SUCCESS`, ReviewCase `DISPUTE_OPENED` phải kèm
   `evidence.refund_overlap = true` để Finance Ops xử lý rủi ro khách nhận tiền hai lần
   (`RULE-PAY-023`). Việc Payment từ chối refund command khi dispute đang mở là câu hỏi mở,
   cần thống nhất với Order vì đổi HTTP contract.

8. **Thuật ngữ.** "Reversal" trong tài liệu Payment có hai nghĩa nội bộ: `REFUND_REVERSED`
   (Stripe đảo chiều một refund đã `succeeded`) và journal `REVERSAL`/`REFUND_REVERSAL` (bút
   toán đảo). Cả hai khác *charge reversal* của Stripe. Không đổi tên enum/contract.

## Hệ quả

- Không còn chốt `SUCCESS` sớm khi refund còn `pending`; refund bị hủy hoặc cần hành động
  không còn kẹt ở `PROCESSING`.
- Reversal trễ tới 30 ngày vẫn được phát hiện khi webhook bị miss, với chi phí bounded.
- Quét reconciliation thêm một lượt `Refund.retrieve` cho mỗi refund `SUCCESS` trong 35
  ngày mỗi giờ; cần theo dõi rate limit Stripe (xem `operations.md`).
- Phương thức thanh toán ngoài thẻ bị chặn cho tới khi có ADR mở rộng.

## Đã triển khai ở code

- `RefundProviderOutcome` (`application/port/out`): phân loại `Refund.status`
  (`SUCCEEDED`/`FAILED` gồm `canceled`/`REQUIRES_ACTION`/`NON_TERMINAL`), mặc định
  `merchant_request` cho refund `canceled` thiếu `failure_reason`.
- `StripeWebhookEventHandler.refund`: `charge.refunded` chỉ là tín hiệu và phải
  `Refund.retrieve`; `refund.updated` `succeeded` chốt `R2`; `canceled` → `R3`/`R5`;
  `requires_action` → `R5` nếu đã `SUCCESS`, ngược lại mở `RECONCILIATION_DRIFT`.
  `createDispute` thêm `evidence.refund_overlap`.
- `StripeWebhookEventParser`/`StripeProviderCurrentFactAdapter`: `charge.refunded` không
  còn mang balance transaction của charge; refund thành công dùng `balance_transaction`,
  refund thất bại dùng `failure_balance_transaction`.
- `RefundPollerService`/`ReconciliationService`: phân loại theo `Refund.status`;
  `requires_action`, `pending` bất thường ở refund `SUCCESS` mở drift. Đường reconciliation
  của reversal giờ mở ReviewCase `REFUND_REVERSED` và bật `requires_manual_review`
  (trước đó thiếu, không khớp `domain.md §6.2`).
- `ReconciliationPort.findRefunds` + `JdbcReconciliationAdapter`: refund `SUCCESS` chỉ quét
  trong `payment.reconciliation.refund-success-lookback` (`P35D`), theo `provider_synced_at`
  cũ nhất trước; migration `V2__refund_success_reconciliation_index.sql`.
- `StripePaymentProviderAdapter`: `payment_method_types` lấy từ
  `payment.stripe.payment-method-types` (mặc định `card`).

## Việc còn lại

- Rà `StripeWebhookEventHandler.refundJournal`/`ReconciliationService.refundJournal`: hai
  đường dùng `source_id` khác nhau cho `REFUND_REVERSAL` (`re_x` và `re_x:reversal`); hiện
  FSM chặn ghi đôi nhưng nên thống nhất.
- Đưa `payment.stripe.payment-method-types` và Dashboard payment-method configuration vào
  checklist triển khai; `PaymentIntent` hiện đã ghim `card` ở request nhưng cấu hình
  Dashboard cần khớp.
- Quyết định có chặn refund command khi dispute đang mở hay không (cần thống nhất Order).

## Xác minh

Test với fixture Stripe theo API version pin: `charge.refunded` khi refund `pending` không
chốt `SUCCESS`; `refund.updated succeeded` chốt `R2`; `canceled` → `R3`; `SUCCESS` rồi
`failed`/`canceled`/`requires_action` → `R5`/`T9`; refund `SUCCESS` cũ 20 ngày vẫn được
reconcile; refund mới không bị bỏ đói khi có nhiều hơn `batch-size` refund `SUCCESS`; journal
`REFUND_REVERSAL` dùng `failure_balance_transaction`; dispute mở trên payment đã refund ghi
`refund_overlap`.
