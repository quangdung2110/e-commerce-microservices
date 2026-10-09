package com.ecommerce.checkout;

import com.ecommerce.checkout.support.Fixtures;
import com.ecommerce.checkout.support.RecordedSpans.Span;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The spans of operations.md ("Quan sat"): one per use case, one {@code checkout.dependency} per guarded call
 * and one {@code checkout.redis} per script, nested server span, use case, dependency, HTTP client span.
 * The use case spans are children of Spring's server span, and the work Submit detaches from the request
 * stays inside the Submit span.
 */
class TracingSpansTest extends AbstractCheckoutTest {

    private static final Duration WAIT = Duration.ofSeconds(10);

    /** The one span called {@code name}, waiting for the SDK to have seen it end. */
    private Span only(String name) {
        return await().atMost(WAIT).until(() -> named(name), found -> found.size() == 1).get(0);
    }

    private List<Span> named(String name) {
        return spans.all().stream().filter(span -> span.name().equals(name)).toList();
    }

    private static List<Span> childrenOf(Span parent, List<Span> all) {
        return all.stream().filter(span -> span.parentId().equals(parent.id())).toList();
    }

    private static Span child(Span parent, List<Span> all, String name, String key, String value) {
        List<Span> found = childrenOf(parent, all).stream()
                .filter(span -> span.name().equals(name) && value.equals(span.attributes().get(key))).toList();
        assertThat(found).as("%s %s=%s under %s", name, key, value, parent.name()).hasSize(1);
        return found.get(0);
    }

    private static void assertHttpClientSpanUnder(Span dependency, List<Span> all) {
        assertThat(childrenOf(dependency, all)).as("HTTP client span under checkout.dependency %s",
                dependency.attributes().get("dependency")).anyMatch(span -> span.name().startsWith("http"));
    }

    private List<Span> traceOf(Span span) {
        return spans.all().stream().filter(other -> other.traceId().equals(span.traceId())).toList();
    }

    @Test
    @DisplayName("Preview: checkout.preview under the server span, with a dependency span per wave and a Redis span per script")
    void previewSpans() {
        String user = newUser();
        spans.clear();

        preview(user).expectStatus().isOk();

        Span preview = only("checkout.preview");
        await().atMost(WAIT).until(() -> traceOf(preview).stream().anyMatch(span -> span.id().equals(preview.parentId())));
        List<Span> trace = traceOf(preview);
        assertThat(trace).filteredOn(span -> span.id().equals(preview.parentId())).singleElement()
                .satisfies(server -> assertThat(server.name()).startsWith("http post"));
        for (String dependency : List.of("cart", "user", "product", "inventory")) {
            Span span = child(preview, trace, "checkout.dependency", "dependency", dependency);
            assertThat(span.attributes()).containsEntry("outcome", "success");
        }
        assertThat(childrenOf(preview, trace)).as("no voucher, no Promotion call")
                .noneMatch(span -> "promotion_calculation".equals(span.attributes().get("dependency")));
        child(preview, trace, "checkout.redis", "operation", "peek_preview");
        child(preview, trace, "checkout.redis", "operation", "create_preview");
        assertHttpClientSpanUnder(child(preview, trace, "checkout.dependency", "dependency", "cart"), trace);
        assertThat(preview.error()).isFalse();
    }

    @Test
    @DisplayName("A business problem is an answer, not an error; it names the problem code")
    void businessProblemIsNotAnError() {
        String user = newUser();
        stubCart(Fixtures.map("items", List.of()));
        spans.clear();

        preview(user).expectStatus().isEqualTo(422);

        Span preview = only("checkout.preview");
        assertThat(preview.attributes()).containsEntry("problem", "CART_EMPTY");
        assertThat(preview.error()).isFalse();
    }

    @Test
    @DisplayName("Submit: the claim, revalidation and Order call, including the detached part, stay inside checkout.submit")
    void submitSpans() {
        String user = newUser();
        String token = previewOk(user).path("checkout_token").asText();
        spans.clear();

        submit(user, token).expectStatus().isCreated();

        Span submit = only("checkout.submit");
        await().atMost(WAIT).until(() -> traceOf(submit).stream()
                .anyMatch(span -> "order_post".equals(span.attributes().get("dependency"))));
        List<Span> trace = traceOf(submit);
        Span order = child(submit, trace, "checkout.dependency", "dependency", "order_post");
        assertThat(order.attributes()).containsEntry("outcome", "success");
        assertHttpClientSpanUnder(order, trace);
        child(submit, trace, "checkout.dependency", "dependency", "cart");
        child(submit, trace, "checkout.dependency", "dependency", "product");
        child(submit, trace, "checkout.redis", "operation", "claim");
        child(submit, trace, "checkout.redis", "operation", "finalize");
        assertThat(trace).filteredOn(span -> span.name().equals("checkout.dependency"))
                .allMatch(span -> span.parentId().equals(submit.id()), "every dependency call belongs to the submit");
    }

    @Test
    @DisplayName("Recovery: checkout.recovery wraps the Order read that resolves an unknown result")
    void recoverySpans() {
        String user = newUser();
        String token = previewOk(user).path("checkout_token").asText();
        stubOrderPost(problem(503, "UNAVAILABLE"));
        submit(user, token).expectStatus().isAccepted();
        stubOrderGet("PAYMENT_READY");
        spans.clear();

        status(user, token).expectStatus().isOk();

        Span recovery = only("checkout.recovery");
        List<Span> trace = traceOf(recovery);
        Span read = child(recovery, trace, "checkout.dependency", "dependency", "order_get");
        assertThat(read.attributes()).containsEntry("outcome", "success");
        assertHttpClientSpanUnder(read, trace);
        child(recovery, trace, "checkout.redis", "operation", "finalize");
    }

    @Test
    @DisplayName("Voucher options: checkout.voucher_options wraps the Promotion call")
    void voucherOptionsSpans() {
        String user = newUser();
        String token = previewOk(user).path("checkout_token").asText();
        stubPromotionOptions(Fixtures.voucherOptions(List.of()));
        spans.clear();

        voucherOptions(user, token).expectStatus().isOk();

        Span options = only("checkout.voucher_options");
        List<Span> trace = traceOf(options);
        child(options, trace, "checkout.dependency", "dependency", "promotion_options");
        child(options, trace, "checkout.redis", "operation", "hmget");
    }

    @Test
    @DisplayName("A failing dependency carries its outcome, fails the use case span, and the waves after it never run")
    void failedDependencyFailsTheUseCase() {
        String user = newUser();
        stubCart(problem(503, "UNAVAILABLE"));
        spans.clear();

        preview(user).expectStatus().isEqualTo(503);

        Span preview = only("checkout.preview");
        assertThat(preview.error()).isTrue();
        assertThat(preview.attributes()).containsEntry("problem", "CHECKOUT_UNAVAILABLE");
        Span cart = child(preview, traceOf(preview), "checkout.dependency", "dependency", "cart");
        assertThat(cart.attributes().get("outcome")).isIn("5xx", "connect_error");
        assertThat(childrenOf(preview, traceOf(preview))).noneMatch(span ->
                "user".equals(span.attributes().get("dependency")));
    }
}
