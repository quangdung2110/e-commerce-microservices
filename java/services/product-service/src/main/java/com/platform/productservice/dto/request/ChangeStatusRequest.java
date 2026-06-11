package com.platform.productservice.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Request DTO for changing product status.
 * Command values: SUBMIT, HIDE, SHOW
 */
public record ChangeStatusRequest(
        @NotBlank(message = "Command is required")
        @Pattern(regexp = "SUBMIT|HIDE|SHOW", message = "Command must be one of: SUBMIT, HIDE, SHOW")
        String command
) {}
