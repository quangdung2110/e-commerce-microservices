package com.platform.paymentservice.application.port.out;

import com.platform.paymentservice.domain.ProviderOperation;

public interface PaymentProviderPort {
    ProviderOperationResult dispatch(ProviderOperation operation);

    ProviderOperationResult searchBeforeAnyRecreate(ProviderOperation operation);
}
