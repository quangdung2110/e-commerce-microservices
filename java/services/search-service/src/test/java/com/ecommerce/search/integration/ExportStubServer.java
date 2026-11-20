package com.ecommerce.search.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One in-process HTTP server that plays Product, Inventory and Review for a full reindex: the
 * three export APIs and the Inventory readiness handshake, with one category, one product, one
 * SKU, one availability fact and one rating aggregate. Paths and shapes follow the contracts
 * under {@code contracts/http}.
 *
 * <p>The stub is plain JDK so the integration tests need no extra dependency. It can hold the
 * category export open ({@link #holdCategoryExport}) to keep a run in the middle of its build.
 */
final class ExportStubServer implements AutoCloseable {

    static final String CATEGORY_ID = "11111111-1111-4111-8111-111111111111";
    static final String PRODUCT_ID = "22222222-2222-4222-8222-222222222222";
    static final String SKU_ID = "33333333-3333-4333-8333-333333333333";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpServer server;
    final AtomicInteger categoryCalls = new AtomicInteger();
    final AtomicInteger productCalls = new AtomicInteger();
    final AtomicInteger inventorySessions = new AtomicInteger();
    final AtomicInteger ratingSessions = new AtomicInteger();
    final ConcurrentLinkedQueue<String> readinessCalls = new ConcurrentLinkedQueue<>();

    volatile long catalogRevision = 1;
    volatile long sellingPrice = 28_000_000L;
    private volatile CountDownLatch categoryGate;
    private volatile CountDownLatch categoryEntered = new CountDownLatch(1);

    ExportStubServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("0.0.0.0", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        server.start();
    }

    int port() {
        return server.getAddress().getPort();
    }

    /** Hostname the Search container-side code uses to reach the stub. */
    String baseUrl() {
        return "http://localhost:" + port();
    }

    /** Makes the category export block until {@link #releaseCategoryExport}; a fresh hold forgets earlier entries. */
    void holdCategoryExport() {
        categoryEntered = new CountDownLatch(1);
        categoryGate = new CountDownLatch(1);
    }

    /** Back to the default state between tests: nothing blocks and nothing has been called. */
    void reset() {
        releaseCategoryExport();
        categoryGate = null;
        categoryEntered = new CountDownLatch(1);
        catalogRevision = 1;
        sellingPrice = 28_000_000L;
        categoryCalls.set(0);
        productCalls.set(0);
    }

    boolean awaitCategoryExportEntered(long seconds) throws InterruptedException {
        return categoryEntered.await(seconds, TimeUnit.SECONDS);
    }

    void releaseCategoryExport() {
        CountDownLatch gate = categoryGate;
        if (gate != null) {
            gate.countDown();
        }
    }

    // ------------------------------------------------------------------ routing

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            if (method.equals("GET") && path.equals("/internal/v1/categories/export")) {
                categoryExport(exchange);
            } else if (method.equals("GET") && path.equals("/internal/v1/products/export")) {
                productCalls.incrementAndGet();
                respond(exchange, 200, productPage());
            } else if (method.equals("POST") && path.equals("/exports/inventory/v1/snapshot-sessions")) {
                inventorySessions.incrementAndGet();
                respond(exchange, 201, map(
                        "snapshot_id", "snap-stub", "export_watermark", 5,
                        "created_at", Instant.now().toString(),
                        "expires_at", Instant.now().plusSeconds(86_400).toString()));
            } else if (method.equals("GET") && path.endsWith("/sku-availabilities")) {
                respond(exchange, 200, map(
                        "snapshot_id", "snap-stub", "export_watermark", 5,
                        "items", List.of(map("sku_id", SKU_ID, "is_in_stock", true, "availability_revision", 1,
                                "observed_at", Instant.now().toString())),
                        "next_cursor", null));
            } else if (method.equals("GET") && path.equals("/exports/inventory/v1/changes")) {
                respond(exchange, 200, map("items", List.of(), "minimum_retained_sequence", 0,
                        "latest_sequence", 5, "next_after_sequence", null));
            } else if (path.startsWith("/exports/inventory/v1/search-projection-readiness/")) {
                readinessCalls.add(method + " " + path);
                if (method.equals("PUT")) {
                    respond(exchange, 200, map("sku_id", SKU_ID, "catalog_revision", catalogRevision,
                            "registered_at", Instant.now().toString()));
                } else {
                    exchange.sendResponseHeaders(204, -1);
                }
            } else if (method.equals("POST") && path.equals("/internal/v1/reviews/rating-aggregate-exports")) {
                ratingSessions.incrementAndGet();
                respond(exchange, 201, map(
                        "session_id", "rev-stub", "expires_at", Instant.now().plusSeconds(3_600).toString(),
                        "replay_from_offsets", List.of(offset("t.review.events"))));
            } else if (method.equals("GET") && path.startsWith("/internal/v1/reviews/rating-aggregate-exports/")) {
                respond(exchange, 200, map(
                        "session_id", "rev-stub",
                        "items", List.of(map("product_id", PRODUCT_ID, "average_rating", 4.5, "total_reviews", 10,
                                "total_with_media", 1, "review_revision", 3,
                                "observed_at", Instant.now().toString())),
                        "next_after_product_id", null,
                        "replay_from_offsets", List.of(offset("t.review.events"))));
            } else {
                respond(exchange, 404, map("code", "NOT_FOUND", "path", path));
            }
        } catch (RuntimeException e) {
            respond(exchange, 500, map("code", "STUB_FAILURE", "message", String.valueOf(e.getMessage())));
        }
    }

    private void categoryExport(HttpExchange exchange) throws IOException {
        categoryCalls.incrementAndGet();
        categoryEntered.countDown();
        CountDownLatch gate = categoryGate;
        if (gate != null) {
            try {
                gate.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        Map<String, Object> category = map(
                "category_id", CATEGORY_ID, "parent_id", null, "name", "Dien thoai", "slug", "dien-thoai",
                "category_revision", 1,
                "path", List.of(map("category_id", CATEGORY_ID, "name", "Dien thoai", "slug", "dien-thoai")),
                "is_leaf", true, "facet_definitions", List.of(), "updated_at", Instant.now().toString());
        respond(exchange, 200, map("categories", List.of(category), "next_cursor", null,
                "replay_from_offsets", List.of(offset("t.category.events"))));
    }

    private Map<String, Object> productPage() {
        Map<String, Object> sku = map(
                "sku_id", SKU_ID, "product_id", PRODUCT_ID, "sku_code", "IP15-BLK",
                "base_price_vnd", 34_990_000L, "selling_price_vnd", sellingPrice, "status", "ACTIVE",
                "variant_attributes", map("color", "Black"), "thumbnail_storage_key", null,
                "updated_at", Instant.now().toString());
        Map<String, Object> product = map(
                "product_id", PRODUCT_ID, "category_id", CATEGORY_ID, "name", "iPhone 15", "slug", "iphone-15",
                "description", "A phone", "attributes", map("brand", "Apple"), "status", "ACTIVE",
                "catalog_revision", catalogRevision, "skus", List.of(sku), "updated_at", Instant.now().toString());
        return map("products", List.of(product), "next_cursor", null,
                "replay_from_offsets", List.of(offset("t.product.events")));
    }

    private static Map<String, Object> offset(String topic) {
        return map("topic", topic, "partition", 0, "offset", 0);
    }

    private static Map<String, Object> map(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private static void respond(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        releaseCategoryExport();
        server.stop(0);
    }
}
