package com.platform.productservice.service;

import com.platform.productservice.common.DomainException;
import com.platform.productservice.common.ErrorCode;
import com.platform.productservice.common.Uuids;
import com.platform.productservice.config.ProductProperties;
import com.platform.productservice.infrastructure.kafka.CatalogOffsetProvider;
import com.platform.productservice.service.support.CatalogExportReader;
import com.platform.productservice.service.support.CursorCodec;
import com.platform.productservice.web.dto.CategoryDtos;
import com.platform.productservice.web.dto.ProductDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Export nội bộ cho Search full reindex (WF-PRODUCT-003, ADR-010, ADR-014).
 *
 * <p>Trang đầu mở một export session: Product chụp replay offsets của topic dữ
 * liệu tương ứng <b>trước khi</b> scan, rồi chụp một upper bound UUIDv7. Các trang
 * sau đi theo ID tăng dần tới bound đó nên export luôn kết thúc, và cursor mang
 * lại đúng tập offsets ban đầu nên mọi trang — kể cả khi retry một trang — trả về
 * cùng một boundary.
 *
 * <p>Đây không phải snapshot bất biến của database. Search import trọn vẹn snapshot
 * rồi seek inclusive từ {@code replay_from_offsets} trong pha Catch-up;
 * {@code catalog_revision} và {@code category_revision} làm phần chồng lấn giữa
 * snapshot và event trở thành no-op. Product không consume ngược lại gì ở đây và
 * không tạo transaction phân tán PostgreSQL–Kafka.
 */
@Service
@RequiredArgsConstructor
public class CatalogExportService {

    private static final UUID FIRST_ID = new UUID(0L, 0L);

    private final CatalogExportReader reader;
    private final CatalogOffsetProvider offsetProvider;
    private final CursorCodec cursorCodec;
    private final ProductProperties properties;

    public ProductDtos.ProductExportPageResponse exportProducts(String cursor, int limit) {
        CursorCodec.ExportCursor session = session(cursor, properties.getExport().getProductTopic());
        List<ProductDtos.ProductResponse> page =
                reader.products(session.lastId(), session.bound(), limit);
        String nextCursor = page.size() < limit ? null : cursorCodec.encodeExport(
                page.get(page.size() - 1).productId(), session.bound(), session.offsets());
        return new ProductDtos.ProductExportPageResponse(page, nextCursor, session.offsets());
    }

    /** Search đọc export category trước export Product khi xử lý reindex request. */
    public CategoryDtos.CategoryExportPageResponse exportCategories(String cursor, int limit) {
        CursorCodec.ExportCursor session = session(cursor, properties.getExport().getCategoryTopic());
        List<CategoryDtos.CategoryExportResponse> page =
                reader.categories(session.lastId(), session.bound(), limit);
        String nextCursor = page.size() < limit ? null : cursorCodec.encodeExport(
                page.get(page.size() - 1).categoryId(), session.bound(), session.offsets());
        return new CategoryDtos.CategoryExportPageResponse(page, nextCursor, session.offsets());
    }

    /**
     * Mở session mới ở trang đầu, hoặc khôi phục session đã có từ cursor.
     *
     * <p>Trang đầu chụp offsets trước khi scan; trang sau <b>không</b> chụp lại,
     * vì boundary mới sẽ bỏ sót event nằm giữa hai lần chụp.
     */
    private CursorCodec.ExportCursor session(String cursor, String topic) {
        if (cursor == null || cursor.isBlank()) {
            return new CursorCodec.ExportCursor(FIRST_ID, Uuids.next(),
                    offsetProvider.replayOffsets(topic));
        }
        CursorCodec.ExportCursor resumed = cursorCodec.decodeExport(cursor);
        if (!resumed.topic().equals(topic)) {
            // Cursor của export kia sẽ trả boundary sai topic; từ chối thay vì
            // để Search replay nhầm stream.
            throw DomainException.of(ErrorCode.INVALID_CURSOR,
                    "Export cursor belongs to a different export");
        }
        return resumed;
    }
}
