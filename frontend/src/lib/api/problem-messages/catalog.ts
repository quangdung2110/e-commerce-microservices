/** Product Service (quản trị catalog) và Search Service. */
export const PRODUCT_MESSAGES: Record<string, string> = {
  PRODUCT_DETAIL_NOT_FOUND: "Không tìm thấy sản phẩm đang bán.",
  PRODUCT_DETAIL_UNAVAILABLE: "Thông tin sản phẩm đang tạm gián đoạn. Vui lòng thử lại.",
  PRODUCT_NOT_FOUND: "Không tìm thấy sản phẩm.",
  PRODUCT_DELETED: "Sản phẩm này đã bị xoá.",
  PRODUCT_REVISION_MISMATCH:
    "Sản phẩm vừa được người khác thay đổi. Hãy tải lại để xem bản mới trước khi sửa tiếp.",
  PRODUCT_NOT_SUBMITTABLE: "Sản phẩm chưa đủ điều kiện để chuyển trạng thái.",
  SKU_NOT_FOUND: "Không tìm thấy SKU.",
  SKU_DELETED: "SKU này đã bị xoá.",
  SKU_CODE_ALREADY_EXISTS: "Mã SKU đã được dùng. Vui lòng chọn mã khác.",
  SKU_LAST_DELETE_FORBIDDEN: "Không thể xoá SKU cuối cùng. Mỗi sản phẩm cần ít nhất một SKU.",
  INVALID_PRICE: "Giá chưa hợp lệ.",
  MEDIA_NOT_FOUND: "Không tìm thấy ảnh hoặc video.",
  CATEGORY_NOT_FOUND: "Không tìm thấy danh mục.",
  CATEGORY_NOT_EMPTY: "Danh mục vẫn còn danh mục con hoặc sản phẩm nên chưa xoá được.",
  CATEGORY_NOT_LEAF: "Sản phẩm chỉ được gán vào danh mục cấp cuối.",
  CATEGORY_CYCLE_DETECTED: "Không thể chọn danh mục con làm danh mục cha.",
  SLUG_ALREADY_EXISTS: "Slug này đã được dùng. Vui lòng chọn slug khác.",
  ATTRIBUTE_NOT_FOUND: "Không tìm thấy thuộc tính.",
  ATTRIBUTE_IN_USE: "Thuộc tính đang được sử dụng nên chưa xoá được.",
  ATTRIBUTE_OPTION_NOT_FOUND: "Không tìm thấy lựa chọn của thuộc tính.",
  ATTRIBUTE_CODE_ALREADY_EXISTS: "Mã thuộc tính đã được dùng. Vui lòng chọn mã khác.",
  ATTRIBUTE_OPTION_CODE_ALREADY_EXISTS:
    "Mã lựa chọn đã được dùng trong thuộc tính này. Vui lòng chọn mã khác.",
  INVALID_ATTRIBUTES: "Giá trị thuộc tính chưa đúng với cấu hình của danh mục.",
  CATALOG_CONSTRAINT_VIOLATION:
    "Thay đổi xung đột với dữ liệu catalog hiện tại. Vui lòng tải lại và kiểm tra.",
  CATALOG_UNAVAILABLE: "Catalog đang tạm gián đoạn. Vui lòng thử lại sau ít phút.",
};

export const SEARCH_MESSAGES: Record<string, string> = {
  INVALID_CURSOR: "Trang kết quả đã hết hạn. Vui lòng tìm lại từ đầu.",
  INVALID_QUERY_PARAMETER: "Bộ lọc tìm kiếm chưa hợp lệ.",
  SEARCH_TIMEOUT: "Tìm kiếm mất quá nhiều thời gian. Vui lòng thử lại.",
  ELASTICSEARCH_UNAVAILABLE: "Tìm kiếm đang tạm gián đoạn. Vui lòng thử lại sau ít phút.",
  REINDEX_ALREADY_RUNNING: "Đang có một lượt reindex chạy. Hãy chờ lượt đó kết thúc.",
  REINDEX_RUN_NOT_FOUND: "Không tìm thấy lượt reindex.",
};
