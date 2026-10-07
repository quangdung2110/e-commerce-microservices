package com.platform.paymentservice.adapter.in.webhook;

import com.platform.paymentservice.application.service.WebhookIngressResult;
import com.platform.paymentservice.application.service.WebhookIngressService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

@RestController
final class StripeWebhookController {
    private final ObjectProvider<WebhookIngressService> ingress;
    private final int maximumBodyBytes;

    StripeWebhookController(ObjectProvider<WebhookIngressService> ingress,
                            @Value("${payment.stripe.webhook-max-bytes:262144}") int maximumBodyBytes) {
        this.ingress = ingress;
        if (maximumBodyBytes < 1) throw new IllegalArgumentException("webhook max bytes must be positive");
        this.maximumBodyBytes = maximumBodyBytes;
    }

    @PostMapping(path = "/v1/payments/webhook/stripe")
    ResponseEntity<Void> ingest(
            HttpServletRequest request,
            @RequestHeader(name = "Stripe-Signature", required = false) String signature) {
        if (request.getContentLengthLong() > maximumBodyBytes) return response(HttpStatus.BAD_REQUEST);
        final byte[] exactBody;
        try {
            exactBody = readBounded(request.getInputStream());
        } catch (BodyTooLarge | IOException invalid) {
            return response(HttpStatus.BAD_REQUEST);
        }
        WebhookIngressService service = ingress.getIfAvailable();
        if (service == null) return response(HttpStatus.SERVICE_UNAVAILABLE);
        WebhookIngressResult result = service.ingest(exactBody, signature);
        if (result instanceof WebhookIngressResult.Accepted
                || result instanceof WebhookIngressResult.DuplicateTerminal) {
            return response(HttpStatus.OK);
        }
        if (result instanceof WebhookIngressResult.Rejected) return response(HttpStatus.BAD_REQUEST);
        return response(HttpStatus.SERVICE_UNAVAILABLE);
    }

    private byte[] readBounded(InputStream input) throws IOException, BodyTooLarge {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximumBodyBytes, 8192));
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > maximumBodyBytes) throw new BodyTooLarge();
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static ResponseEntity<Void> response(HttpStatus status) {
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    private static final class BodyTooLarge extends Exception {}
}
