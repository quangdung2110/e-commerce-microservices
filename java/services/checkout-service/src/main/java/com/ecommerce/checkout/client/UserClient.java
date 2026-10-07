package com.ecommerce.checkout.client;

import com.ecommerce.checkout.config.CheckoutProperties;
import com.ecommerce.checkout.resilience.ContractViolationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Optional;

/**
 * User address snapshot on the internal listener:
 * {@code GET /internal/v1/users/{user_id}/addresses/{address_id}/checkout-snapshot}. User verifies
 * ownership; {@code 404} means the address does not belong to the user and is a business result
 * ({@code ADDRESS_NOT_FOUND}), not an error. A {@code 429} from User's own limiter counts as a
 * dependency failure (interfaces.md).
 */
@Component
public class UserClient {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public UserClient(WebClient.Builder builder, CheckoutProperties properties, ObjectMapper objectMapper) {
        this.webClient = UpstreamHttp.build(builder, "user", properties.endpoints().userBaseUrl(),
                properties.bulkheads().user());
        this.objectMapper = objectMapper;
    }

    public record Address(String addressId, String recipientName, String recipientPhone, String addressLine,
                          String provinceCode, String districtCode, String wardCode) {

        Address validated(String requestedAddressId) {
            if (blank(addressId) || !addressId.equalsIgnoreCase(requestedAddressId)) {
                throw new ContractViolationException("address snapshot: address_id does not match the request");
            }
            if (blank(recipientName) || recipientName.length() > 200
                    || blank(recipientPhone) || recipientPhone.length() < 5 || recipientPhone.length() > 32
                    || blank(addressLine) || addressLine.length() > 500
                    || blank(provinceCode) || provinceCode.length() > 32
                    || blank(districtCode) || districtCode.length() > 32
                    || blank(wardCode) || wardCode.length() > 32) {
                throw new ContractViolationException("address snapshot: field missing or out of range");
            }
            return this;
        }

        private static boolean blank(String value) {
            return value == null || value.isBlank();
        }
    }

    public Mono<Optional<Address>> fetch(String userId, String addressId, RequestContext context) {
        return webClient.get()
                .uri("/internal/v1/users/{userId}/addresses/{addressId}/checkout-snapshot", userId, addressId)
                .accept(MediaType.APPLICATION_JSON)
                .header(UpstreamHttp.REQUEST_ID_HEADER, context.requestId())
                .exchangeToMono(response -> {
                    if (response.statusCode().value() == HttpStatus.NOT_FOUND.value()) {
                        return response.releaseBody().thenReturn(Optional.<Address>empty());
                    }
                    if (!response.statusCode().is2xxSuccessful()) {
                        return UpstreamHttp.unexpectedStatus(response);
                    }
                    return UpstreamHttp.readBody(response, Address.class, objectMapper)
                            .map(address -> Optional.of(address.validated(addressId)));
                });
    }
}
