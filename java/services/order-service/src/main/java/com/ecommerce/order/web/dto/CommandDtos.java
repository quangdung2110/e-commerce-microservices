package com.ecommerce.order.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Request bodies of contracts/http/order/v1 commands. */
public final class CommandDtos {

    private CommandDtos() {
    }

    public record BuyerCancellationRequest(
            @NotNull @Pattern(regexp = "USER_CANCEL", message = "must be USER_CANCEL") String reasonCode) {
    }

    public record AdminCancellationRequest(
            @NotNull @Pattern(regexp = "ADMIN_FORCE_CANCEL", message = "must be ADMIN_FORCE_CANCEL") String reasonCode) {
    }

    public record ReturnLineRequest(
            @NotNull @Pattern(regexp = "^[1-9][0-9]{0,18}$") String orderLineId,
            @NotNull @Min(1) Integer quantity) {
    }

    public record BuyerReturnRequest(
            @NotNull @Size(min = 1, max = 100) List<@Valid @NotNull ReturnLineRequest> lines,
            @NotBlank @Size(max = 100) String reasonCode,
            @Size(max = 1000) String reasonDetails) {
    }

    public record AdminPreDispatchRefundRequest(
            @NotBlank @Size(max = 100) String reasonCode,
            @Size(max = 1000) String reasonDetails) {
    }

    public record CreatePaymentAttemptRequest(
            @Size(max = 2048) @Pattern(regexp = "^https?://\\S+$", message = "must be an absolute http(s) URI")
            String returnUrl) {
    }
}
