package com.platform.productservice.service;

/**
 * Kết quả của một command con trong Product aggregate: body trả về cho client
 * kèm ETag mới của aggregate, vì mọi mutation đều tăng {@code catalog_revision}
 * của Product chứ không phải của resource con.
 */
public record CommandResult<T>(T body, String etag) {
}
