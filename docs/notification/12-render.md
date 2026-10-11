# Template và render

**Trạng thái:** `implemented` (phase 1) — runtime Go (D-15).

## 1. Ranh giới của Render

Render nhận một `notification_delivery` đã được claim cùng `template_data` (từ request, hoặc từ Auth `content` API với template Auth) và trả về **một trong hai sản phẩm**: email (`subject`, `html`, `text`) hoặc in-app (`title`, `body`, `deep_link`). Nó không quyết định có gửi hay không (Decide đã làm), không biết provider, không ghi DB.

Ba tính chất bắt buộc:

| Tính chất | Vì sao |
|---|---|
| **Thuần tuý** — cùng `(template, version, locale, template_data)` → cùng kết quả | Render lại được khi điều tra thay vì lưu bản đã gửi ([11-privacy.md](./11-privacy.md) §1); snapshot test được |
| **Fail-fast lúc khởi động**, không phải lúc gửi | Template lỗi cú pháp hay thiếu biến bắt buộc phải chặn deploy; phát hiện lúc gửi nghĩa là DLQ đầy `render_failed` giữa đêm |
| **Không tin `template_data`** | Dữ liệu đến từ service khác qua Kafka; tên sản phẩm có thể chứa `<script>`; escaping theo ngữ cảnh là bắt buộc |

## 2. Engine: `html/template` + `text/template`

Chọn thư viện chuẩn của Go, không dùng engine bên thứ ba.

| Tiêu chí | `html/template` | Engine khác (Pongo2, Jet, Mustache) |
|---|---|---|
| Escaping | **Theo ngữ cảnh**: cùng một giá trị được escape khác nhau trong text node, attribute, URL, JS — tự động, không cần nhớ gọi filter | Escape HTML chung, hoặc phụ thuộc developer nhớ `|escape` |
| Injection từ `template_data` | `name = "<img src=x onerror=...>"` thành text vô hại; URL không phải `http(s)` bị thay bằng `#ZgotmplZ` | Tuỳ engine |
| Phụ thuộc | Không | Thêm dependency cần theo dõi CVE |
| Cú pháp | Khô, ít "sugar"; logic phức tạp phải đưa vào Go func | Dễ viết hơn |

Cái giá là cú pháp khô — chấp nhận, vì template của Notification không có logic phức tạp: hiển thị field, lặp danh sách item, định dạng tiền và ngày. Mọi thứ khó hơn (định dạng tiền VND, ngày theo locale, rút gọn chuỗi) là **template function** viết bằng Go và có unit test.

Bản `text` của email dùng `text/template` trên cùng dữ liệu; bản in-app cũng dùng `text/template` (không HTML).

## 3. Template nằm trong repo, compile vào binary

| | Repo + `embed` | Bảng DB |
|---|---|---|
| Review thay đổi | PR, diff đọc được, checklist [11-privacy.md](./11-privacy.md) §7 | Ai sửa lúc nào không ai biết |
| Fail-fast | Compile và validate ở `go test` và lúc khởi động | Chỉ biết lúc render |
| Đổi nội dung không deploy | Không | Có |
| `template_version` | Deterministic từ nội dung | Phải tự quản |

Chọn **repo + `embed`**. "Đổi template không cần deploy" là tính năng của hệ thống marketing trưởng thành với đội content riêng; đồ án không có nhu cầu đó, và cái giá là mất kiểm soát thay đổi trên thứ trực tiếp gửi tới user.

### Cấu trúc

```
go/services/notification-service/internal/catalog/
  catalog.yaml                -- bản sao read-only của contracts/events/notification/v1/templates/catalog.yaml
                                 (test khẳng định byte-equal)
  schemas/<ID>.schema.json    -- 15 JSON Schema template_data: 5 ORDER_* copy nguyên từ contracts (test byte-equal),
                                 10 còn lại viết tại đây vì contract nguồn (Auth OpenAPI, Review, Cart) không có schema riêng
go/services/notification-service/internal/templates/
  registry.go                 -- nạp catalog + embed, validate, expose Render()
  model.go                    -- struct typed cho từng template (decode nghiêm, không nhận field lạ)
  funcs.go                    -- template function: money, date, truncate, plural, mask
  ORDER_CONFIRMED/
    vi/
      email.subject.txt
      email.html
      email.txt
      inapp.yaml              -- title, body, deep_link_field
    en/                       -- tuỳ chọn; thiếu → fallback vi
      ...
  ORDER_PAYMENT_FAILED/
    ...
  _layout/
    email.base.html           -- khung chung: header, footer, unsubscribe (bulk); style viết inline sẵn bằng tay
    email.base.txt
  testdata/<ID>/*.json        -- fixture; *.golden.* là snapshot
```

Một template = một thư mục = một `<TEMPLATE_ID>` trong catalog. Test khởi động kiểm tra: mọi template `active`/`proposed` trong catalog có thư mục `vi` đủ file (`inapp.yaml` chỉ bắt buộc khi catalog khai báo kênh `in_app`); không có thư mục thừa ngoài catalog. Phase 1 chỉ ship `vi`; cơ chế fallback và metric cho `en` vẫn có.

### `template_version`

`sha256` của toàn bộ nội dung thư mục template (mọi locale, mọi file) + `_layout`, lấy 12 ký tự đầu, tính lúc build và ghi vào `notification_delivery.template_version` khi `SENT`. Hai binary render khác nhau cho cùng template thì `template_version` khác nhau — đủ để điều tra "email này được render bởi phiên bản nào". Không cần con số version tay.

## 4. Validate lúc khởi động (fail-fast)

`registry.Load()` chạy trong `main()` trước khi mở consumer, và trong `go test`:

1. Parse mọi file bằng `template.New(...).Funcs(funcs).Option("missingkey=error").Parse(...)` — `missingkey=error` làm render **lỗi** khi template tham chiếu field không có, thay vì in `<no value>`.
2. **Render thử** từng template với một fixture `template_data` tối thiểu sinh từ JSON Schema của template (mọi field `required` được điền giá trị mẫu). Lỗi → process thoát với mã khác 0.
3. Kiểm tra giới hạn in-app: `title` ≤ 80, `body` ≤ 200 ký tự với fixture "dài nhất hợp lý" (tên sản phẩm 255 ký tự) — vượt là lỗi build, không phải cắt lúc chạy.
4. Kiểm tra bản `vi` tồn tại cho mọi template; `en` thiếu chỉ cảnh báo.
5. Kiểm tra catalog embed khớp file trong `contracts/` (byte-equal) — tránh hai nguồn lệch nhau.

Hệ quả: lỗi `render_failed` lúc chạy chỉ còn một nguồn — `template_data` hợp lệ theo schema nhưng có giá trị mà template không xử lý được (hiếm; ví dụ số tiền âm). Những lỗi đó là `FAILED (render_failed)` → DLQ + alert, không retry.

## 5. Render lúc chạy

```
Render(delivery, data, locale) → (EmailOut | InAppOut, error)

  tpl   = registry.get(delivery.template, locale)     -- locale thiếu → vi
  model = buildModel(data)                            -- typed struct từ template_data; không truyền map thô
  switch delivery.channel:
    email:  subject = tpl.subject.Execute(model)      -- text/template, một dòng, cắt 200 ký tự
            html    = layout.html.Execute(model + tpl.html)   -- html/template, CSS đã inline ở build
            text    = layout.txt.Execute(model + tpl.txt)     -- text/template
            headers = { List-Unsubscribe (bulk), X-Notification-Delivery-Id }
    in_app: title, body = tpl.inapp.Execute(model)    -- text/template
            deep_link   = data[tpl.inapp.deep_link_field]     -- ví dụ order_url; không lắp URL
```

Ghi chú:

- **Model typed, không map**: `template_data` được unmarshal vào struct Go theo schema; template tham chiếu `.Items`, `.TotalMinor`… Sai tên field là lỗi compile-time của test render thử, không phải lỗi lúc gửi.
- **Không có I/O trong Render**. Dữ liệu đã đủ; không gọi Product để lấy ảnh, không gọi User để lấy tên hiển thị (D-07).
- **CSS inline viết tay** (email client bỏ `<style>`): layout và 15 template dùng `style` attribute trực tiếp, không có `<style>` block — test khẳng định output không chứa `<style`. Không có bước inliner hay MJML ở phase 1: một layout nhỏ cho 15 template không đáng thêm toolchain; nếu sau này cần layout responsive phức tạp, MJML → HTML làm ở build time, không lúc chạy.
- **Bulk có `List-Unsubscribe: <url>`** trỏ tới trang preference của frontend (`email.unsubscribe_url`; User Service sở hữu preference). `List-Unsubscribe-Post: One-Click` **chưa có ở phase 1**: Gmail gửi POST không xác thực tới URL đó, tức cần một endpoint public nhận token — chưa có trong contract/gateway; bổ sung khi bulk có volume thật (Gmail/Yahoo yêu cầu cho bulk sender > 5.000/ngày).
- Kích thước: HTML email > 100 KB bị Gmail cắt ("[Message clipped]") — test render thử với fixture lớn nhất phải < 100 KB.

## 6. Template function (Go, có unit test)

| Func | Ví dụ | Ghi chú |
|---|---|---|
| `money .TotalMinor .Currency` | `428.000 ₫` | VND không có phần thập phân; dấu chấm ngăn nghìn theo locale `vi` |
| `date .PlacedAt .Timezone` | `20/09/2026 17:15` | Nhận `time.Time`, đổi sang timezone user (mặc định `Asia/Ho_Chi_Minh`) |
| `plural .Quantity "sản phẩm"` | | `vi` không đổi dạng; giữ để `en` dùng |
| `truncate .Name 60` | | Cắt theo rune, thêm `…`; dùng cho in-app |
| `mask .PaymentMethodLabel` | | Không cần — label đã che sẵn từ Order; func tồn tại để test khẳng định không có số thẻ lọt qua (regex 13–19 chữ số → lỗi) |
| `reasonLabel .Reason` | `buyer` → "Bạn đã yêu cầu huỷ đơn hàng" | Dịch enum `reason` của `ORDER_CANCELLED` sang câu tiếng Việt; giá trị ngoài enum là lỗi render |
| `dict "k" v ...` | | Dựng map nhỏ để truyền tham số cho sub-template; không đọc dữ liệu ngoài |

Không có func nào gọi ra ngoài, đọc file hay đọc env.

## 7. Locale

- `vi` bắt buộc cho mọi template; `en` tuỳ chọn. Locale lấy từ Auth `notification-contact` (`locale?`), thiếu → `vi` ([08-decide.md](./08-decide.md) §5).
- Fallback theo file: thiếu `en/email.html` → dùng `vi/email.html`; không lỗi, có metric `render_locale_fallback_total`.
- Không dùng thư viện i18n message-catalog (gettext, go-i18n) ở phase 1: mỗi template là một file trọn vẹn theo locale — dễ đọc, dễ review, đủ cho 15 template × 2 locale. Đổi sang message catalog khi số template × locale vượt vài trăm.

## 8. Preview và snapshot test

- **CLI** `notifctl render --template ORDER_CONFIRMED --locale vi --data fixture.json --out ./out/` ghi `email.html`, `email.txt`, `inapp.json`; với `--send-to mailpit` gửi thẳng vào Mailpit để xem trong trình duyệt. Người viết template không cần chạy Kafka.
- **Snapshot test**: mỗi template có ≥ 1 fixture trong `testdata/<TEMPLATE_ID>/*.json`; test render và so với `.golden`. Đổi template → golden đổi → diff hiện trong PR, reviewer thấy chính xác email thay đổi ra sao. Cập nhật golden bằng `go test -update`.
- **Test bảo mật cố định**: fixture có `name = "<script>alert(1)</script>"`, `order_url = "javascript:alert(1)"`; assert HTML output không chứa `<script>` và URL bị vô hiệu (`#ZgotmplZ`).

## 9. Lỗi render và cách xử lý

| Lỗi | Khi nào | Xử lý |
|---|---|---|
| Template không tồn tại / không active | Decide đã chặn (`unknown_template`) | Không tới Render |
| Thiếu file locale `vi` | Khởi động | Process không lên |
| Field thiếu (`missingkey=error`) | Khởi động (fixture) hoặc lúc chạy nếu schema và template lệch | Khởi động: fail. Lúc chạy: `FAILED (render_failed)`, DLQ, alert — đây là bug, không retry |
| Giá trị không xử lý được (tiền âm, ngày zero) | Lúc chạy | `FAILED (render_failed)` như trên |
| In-app vượt giới hạn ký tự | Khởi động với fixture dài nhất | Fail build; lúc chạy `truncate` là lưới an toàn cuối |
| Locale thiếu | Lúc chạy | Fallback `vi`, metric, không lỗi |

Không có lỗi render nào là retryable: cùng input sẽ cho cùng lỗi. Retry chỉ tốn slot.

## 10. Tóm lại

`html/template` vì escaping theo ngữ cảnh; template trong repo với `embed` vì kiểm soát thay đổi; `template_version` từ hash nội dung; validate và render thử **lúc khởi động** để lỗi template không bao giờ xuất hiện lần đầu ở DLQ; model typed; không I/O; CSS inline viết tay và `List-Unsubscribe` cho bulk; snapshot test làm reviewer thấy email thay đổi ra sao trong PR.
