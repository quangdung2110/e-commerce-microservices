package com.platform.inventoryservice.domain.exception;

import com.platform.inventoryservice.common.ErrorCode;

public class ReservationNotFoundException extends DomainException {

    public ReservationNotFoundException(String context) {
        super(ErrorCode.RESERVATION_NOT_FOUND, "Reservation not found: " + context);
    }
}