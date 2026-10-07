package com.platform.promotionservice.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.platform.promotionservice.common.DomainException;
import com.platform.promotionservice.common.ErrorCode;
import com.platform.promotionservice.domain.enums.ValidityMode;

import java.time.Instant;
import java.util.List;

/** ValidityPolicy discriminated theo {@code mode} (PROMO-BR-008). */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "mode", visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = ValidityDto.CampaignEnd.class, name = "CAMPAIGN_END"),
        @JsonSubTypes.Type(value = ValidityDto.Duration.class, name = "DURATION_AFTER_SAVE"),
        @JsonSubTypes.Type(value = ValidityDto.FixedDate.class, name = "FIXED_DATE")
})
public sealed interface ValidityDto permits ValidityDto.CampaignEnd, ValidityDto.Duration, ValidityDto.FixedDate {

    String mode();

    record Storage(ValidityMode mode, Long validForSeconds, Instant redeemUntil) {
    }

    Storage toStorage();

    static ValidityDto fromStorage(ValidityMode mode, Long validForSeconds, Instant redeemUntil) {
        return switch (mode) {
            case CAMPAIGN_END -> new CampaignEnd("CAMPAIGN_END");
            case DURATION_AFTER_SAVE -> new Duration("DURATION_AFTER_SAVE", validForSeconds);
            case FIXED_DATE -> new FixedDate("FIXED_DATE", redeemUntil);
        };
    }

    record CampaignEnd(@JsonProperty("mode") String mode) implements ValidityDto {
        @Override
        public Storage toStorage() {
            return new Storage(ValidityMode.CAMPAIGN_END, null, null);
        }
    }

    record Duration(@JsonProperty("mode") String mode,
                    @JsonProperty("valid_for_seconds") Long validForSeconds) implements ValidityDto {
        @Override
        public Storage toStorage() {
            if (validForSeconds == null || validForSeconds < 1) {
                throw new DomainException(ErrorCode.INVALID_REQUEST, "validity.valid_for_seconds must be >= 1",
                        List.of(new DomainException.FieldError("validity.valid_for_seconds", "must be >= 1")));
            }
            return new Storage(ValidityMode.DURATION_AFTER_SAVE, validForSeconds, null);
        }
    }

    record FixedDate(@JsonProperty("mode") String mode,
                     @JsonProperty("redeem_until") Instant redeemUntil) implements ValidityDto {
        @Override
        public Storage toStorage() {
            if (redeemUntil == null) {
                throw new DomainException(ErrorCode.INVALID_REQUEST, "validity.redeem_until is required",
                        List.of(new DomainException.FieldError("validity.redeem_until", "is required")));
            }
            return new Storage(ValidityMode.FIXED_DATE, null, redeemUntil);
        }
    }
}
