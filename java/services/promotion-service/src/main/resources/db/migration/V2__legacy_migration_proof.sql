-- Proof của legacy technical cancellation (docs/promotion/data.md §3.8, ADR-013/017/019).
-- CANCEL_CONFIRMED chỉ chạy dưới capability promotion-legacy-migrator kèm bằng chứng bất biến rằng
-- luồng lịch sử chưa vượt Payment financial watermark: receipt giữ ID bản ghi migration và digest
-- SHA-256 của bằng chứng; CONFIRM không có hai cột này.
ALTER TABLE promotion_command_receipts
    ADD COLUMN migration_record_id           UUID     NULL,
    ADD COLUMN pre_watermark_evidence_sha256 CHAR(64) NULL;

-- NOT VALID: receipt CANCEL_CONFIRMED tạo trước V2 (khi guard chưa tồn tại) không có proof và không thể
-- bịa lại; ràng buộc áp dụng cho mọi dòng mới/được sửa.
ALTER TABLE promotion_command_receipts
    ADD CONSTRAINT chk_receipts_migration_proof CHECK (
        (operation_type = 'CANCEL_CONFIRMED'
            AND migration_record_id IS NOT NULL AND pre_watermark_evidence_sha256 IS NOT NULL)
        OR (operation_type = 'CONFIRM'
            AND migration_record_id IS NULL AND pre_watermark_evidence_sha256 IS NULL)
    ) NOT VALID;
