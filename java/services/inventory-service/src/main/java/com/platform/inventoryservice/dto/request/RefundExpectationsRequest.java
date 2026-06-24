package com.platform.inventoryservice.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record RefundExpectationsRequest(
    @Min(1) long orderId,
    @NotBlank @Size(max = 64) String refundRequestId,
    @NotEmpty List<Long> orderItemIds
) {}
