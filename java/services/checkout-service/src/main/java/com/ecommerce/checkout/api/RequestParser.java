package com.ecommerce.checkout.api;

import com.ecommerce.checkout.problem.ApiException;
import com.ecommerce.checkout.problem.Problem;
import com.ecommerce.checkout.problem.ProblemCode;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Strict parsing of request bodies and identifiers (interfaces.md: "Body/header sai contract, thieu
 * Idempotency-Key, field la" is {@code 400 INVALID_REQUEST}). Unlike the tolerant reader used for
 * providers, a client body may not carry an unknown field such as {@code quantity} or
 * {@code payment_method}, and a number is never coerced into a string.
 */
@Component
public class RequestParser {

    private static final Pattern UUID_FORM =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final ObjectMapper strict;

    public RequestParser(ObjectMapper mapper) {
        this.strict = mapper.copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
                .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, true)
                .configure(MapperFeature.ALLOW_COERCION_OF_SCALARS, false)
                .configure(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, true);
        this.strict.coercionConfigFor(String.class).setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
    }

    /** Body of {@code POST /api/v1/checkout/preview}; only these three identifiers are accepted. */
    public record PreviewBody(String previousCheckoutToken, String deliveryAddressId, String promotionId) {
    }

    /** Body of {@code POST /api/v1/checkout/submit}. */
    public record SubmitBody(String checkoutToken) {
    }

    public PreviewBody preview(byte[] body) {
        PreviewBody parsed = read(body, PreviewBody.class);
        String address = requireUuid(parsed.deliveryAddressId(), "delivery_address_id");
        String previous = parsed.previousCheckoutToken() == null
                ? null : requireUuid(parsed.previousCheckoutToken(), "previous_checkout_token");
        String promotion = parsed.promotionId();
        if (promotion != null && (promotion.isEmpty() || promotion.length() > 128)) {
            throw invalid("promotion_id must be 1 to 128 characters", "promotion_id");
        }
        return new PreviewBody(previous, address, promotion);
    }

    public SubmitBody submit(byte[] body) {
        SubmitBody parsed = read(body, SubmitBody.class);
        return new SubmitBody(requireUuid(parsed.checkoutToken(), "checkout_token"));
    }

    /** Canonical lowercase form of a UUID, or {@code null} when {@code value} is not one. */
    public static String uuidOrNull(String value) {
        if (value == null || !UUID_FORM.matcher(value).matches()) {
            return null;
        }
        return UUID.fromString(value).toString();
    }

    /** {@code Idempotency-Key}: a UUID, required. */
    public String idempotencyKey(String header) {
        String value = uuidOrNull(header == null ? null : header.trim());
        if (value == null) {
            throw invalid("Idempotency-Key must be a UUID", "Idempotency-Key");
        }
        return value;
    }

    private <T> T read(byte[] body, Class<T> type) {
        if (body == null || body.length == 0) {
            throw invalid("The request body is required", "body");
        }
        try {
            T value = strict.readValue(body, type);
            if (value == null) {
                throw invalid("The request body is required", "body");
            }
            return value;
        } catch (UnrecognizedPropertyException e) {
            throw invalid("Unsupported field: " + e.getPropertyName(), e.getPropertyName());
        } catch (JsonParseException e) {
            throw invalid("The request body is not valid JSON", "body");
        } catch (JsonMappingException e) {
            String field = e.getPath().isEmpty() ? "body" : e.getPath().get(0).getFieldName();
            throw invalid("Invalid value for " + field, field == null ? "body" : field);
        } catch (IOException e) {
            throw invalid("The request body could not be read", "body");
        }
    }

    private static String requireUuid(String value, String field) {
        String canonical = uuidOrNull(value);
        if (canonical == null) {
            throw invalid(field + " must be a UUID", field);
        }
        return canonical;
    }

    private static ApiException invalid(String detail, String field) {
        return new ApiException(Problem.of(ProblemCode.INVALID_REQUEST, detail, Map.of("errors",
                List.of(Map.of("field", field, "message", detail)))));
    }
}
