-- cleanup-outbox.sql
-- Script dọn dẹp outbox events cũ đã được Debezium xử lý
-- Chạy thông qua cron job hàng ngày (khuyến nghị: 3 AM)
--
-- Giữ lại events trong 7 ngày để đảm bảo Debezium đã poll và publish
-- (Debezium thường poll trong vài giây đến vài phút)

-- Xóa outbox events đã tạo từ hơn 7 ngày
DO $$
DECLARE
    deleted_count INTEGER;
    retained_count INTEGER;
    cleanup_threshold TIMESTAMPTZ;
BEGIN
    -- Tính threshold
    cleanup_threshold := NOW() - INTERVAL '7 days';

    -- Đếm records sẽ được giữ lại
    SELECT COUNT(*) INTO retained_count
    FROM outbox_events
    WHERE created_at >= cleanup_threshold;

    -- Xóa events cũ
    DELETE FROM outbox_events
    WHERE created_at < cleanup_threshold;

    GET DIAGNOSTICS deleted_count = ROW_COUNT;

    -- Log kết quả
    RAISE NOTICE '========================================';
    RAISE NOTICE 'Outbox Cleanup Summary';
    RAISE NOTICE '========================================';
    RAISE NOTICE 'Cleanup threshold: %', cleanup_threshold;
    RAISE NOTICE 'Records deleted: %', deleted_count;
    RAISE NOTICE 'Records retained: %', retained_count;
    RAISE NOTICE '========================================';
END $$;

-- Vacuum table để giải phóng disk space (chạy async để không block)
VACUUM outbox_events;

-- Ghi log vào bảng audit (tùy chọn)
-- CREATE TABLE IF NOT EXISTS cleanup_audit (
--     id SERIAL PRIMARY KEY,
--     executed_at TIMESTAMPTZ DEFAULT NOW(),
--     records_deleted INTEGER,
--     threshold_date TIMESTAMPTZ
-- );
