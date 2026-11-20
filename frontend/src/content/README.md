# Address catalog

`vietnam-addresses-v1.json` is a local normalized snapshot of the provider's API v1:

- Source: https://provinces.open-api.vn/api/v1/?depth=3
- Provider/documentation: https://provinces.open-api.vn/
- Project/author: https://github.com/hongquan/vn-open-api-provinces — Nguyễn Hồng Quân.
- Retrieved: 2026-10-20. 63 provinces, 696 districts, 10,051 wards as returned by the provider.
- Scope: **legacy three-level addresses before July 2025**, matching the existing required
  `province_code` / `district_code` / `ward_code` backend contract. This is not the current
  two-level API v2. The form labels the catalog version; it does not convert saved addresses.
- Normalization: keep names; pad province/district/ward numeric codes to 2/3/5 digits.
  Comparisons also accept unpadded numeric codes, without rewriting saved payloads.
- Some island districts have no ward in the upstream catalog. The form offers explicit
  manual-code entry, which also preserves addresses absent from this snapshot.

The catalog is bundled, so browsing or submitting an address sends no personal data to
the external provider and has no runtime dependency on it. Refresh the snapshot deliberately,
check parent/child relations and duplicate codes, and record the retrieval date, counts and
hash in `docs/frontend/buyer-gap-fix-evidence-2026-10-20/address-source.json`.
