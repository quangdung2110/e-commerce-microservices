# Contract Validation Tools

## Chạy ngay, không cần cài dependency

Từ repository root:

```powershell
pwsh ./contracts/tools/validate-contracts.ps1
```

Smoke validator kiểm tra:

- JSON có parse được không;
- OpenAPI/AsyncAPI có version marker hợp lệ;
- Protobuf có package versioned và language package options;
- service ID trong interaction catalog có tồn tại;
- đường dẫn contract/docs trong catalog có tồn tại;
- CloudEvents example có đủ field lõi;
- Markdown trong `contracts/` không có relative link bị gãy.

## Tool chuyên dụng khuyến nghị

Smoke test không thay thế semantic linter. Khi cài các CLI sau, script sẽ tự chạy
chúng nếu command có trong `PATH`:

- `buf`: lint Protobuf.
- `redocly`: lint OpenAPI.
- `asyncapi`: validate AsyncAPI.

Chạy strict để bắt buộc cả ba tool tồn tại:

```powershell
pwsh ./contracts/tools/validate-contracts.ps1 -StrictTools
```

Breaking check cho Protobuf sau khi foundation đã có trên branch chính:

```powershell
Push-Location contracts
buf breaking --against '../.git#subdir=contracts'
Pop-Location
```

Generated output nên đặt trong `contracts/generated/` hoặc build directory của
service; thư mục đó bị ignore và không được sửa tay.
