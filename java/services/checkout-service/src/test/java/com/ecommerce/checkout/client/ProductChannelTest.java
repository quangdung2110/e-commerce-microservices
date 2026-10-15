package com.ecommerce.checkout.client;

import com.ecommerce.contracts.product.v1.GetBatchSkusRequest;
import com.ecommerce.contracts.product.v1.GetBatchSkusResponse;
import com.ecommerce.contracts.product.v1.ProductServiceGrpc;
import io.grpc.EquivalentAddressGroup;
import io.grpc.ManagedChannel;
import io.grpc.NameResolver;
import io.grpc.NameResolverProvider;
import io.grpc.NameResolverRegistry;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** contracts ADR-007: the Product channel shares calls over every resolved address and keeps its connection alive. */
class ProductChannelTest {

    private static final String SCHEME = "product-channel-test";

    private final List<Server> servers = new ArrayList<>();
    private final List<AtomicInteger> calls = new ArrayList<>();
    private NameResolverProvider provider;
    private ManagedChannel channel;

    @BeforeEach
    void startBackends() throws Exception {
        List<SocketAddress> addresses = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            AtomicInteger counter = new AtomicInteger();
            Server server = ServerBuilder.forPort(0).addService(new ProductServiceGrpc.ProductServiceImplBase() {
                @Override
                public void getBatchSkus(GetBatchSkusRequest request, StreamObserver<GetBatchSkusResponse> out) {
                    counter.incrementAndGet();
                    out.onNext(GetBatchSkusResponse.getDefaultInstance());
                    out.onCompleted();
                }
            }).build().start();
            servers.add(server);
            calls.add(counter);
            addresses.add(new InetSocketAddress("127.0.0.1", server.getPort()));
        }
        provider = new NameResolverProvider() {
            @Override
            protected boolean isAvailable() {
                return true;
            }

            @Override
            protected int priority() {
                return 10;
            }

            @Override
            public String getDefaultScheme() {
                return SCHEME;
            }

            @Override
            public NameResolver newNameResolver(URI target, NameResolver.Args args) {
                if (!SCHEME.equals(target.getScheme())) {
                    return null;
                }
                return new NameResolver() {
                    @Override
                    public String getServiceAuthority() {
                        return "product";
                    }

                    @Override
                    public void start(Listener2 listener) {
                        listener.onResult(ResolutionResult.newBuilder()
                                .setAddresses(addresses.stream().map(EquivalentAddressGroup::new).toList())
                                .build());
                    }

                    @Override
                    public void shutdown() {
                    }
                };
            }
        };
        NameResolverRegistry.getDefaultRegistry().register(provider);
    }

    @AfterEach
    void stop() throws Exception {
        if (channel != null) {
            channel.shutdownNow().awaitTermination(2, TimeUnit.SECONDS);
        }
        NameResolverRegistry.getDefaultRegistry().deregister(provider);
        for (Server server : servers) {
            server.shutdownNow().awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void callsAreSpreadOverEveryResolvedAddress() {
        channel = ProductClient.channelFor(SCHEME + ":///product");
        ProductServiceGrpc.ProductServiceBlockingStub stub = ProductServiceGrpc.newBlockingStub(channel);

        for (int i = 0; i < 30; i++) {
            stub.getBatchSkus(GetBatchSkusRequest.newBuilder().addSkuIds("SKU-A").build());
        }

        // pick_first would put all 30 on one backend.
        for (AtomicInteger counter : calls) {
            assertThat(counter.get()).isBetween(7, 13);
        }
    }

    @Test
    void keepaliveStaysAboveWhatProductPermitsAndBelowItsOwnInterval() {
        // Product permits a ping every 20 s (grpc.server.permit-keep-alive-time); faster pings get GOAWAY too_many_pings.
        assertThat(ProductClient.KEEP_ALIVE_TIME.toSeconds()).isGreaterThan(20);
        assertThat(ProductClient.KEEP_ALIVE_TIMEOUT).isLessThan(ProductClient.KEEP_ALIVE_TIME).isPositive();
        assertThat(ProductClient.LOAD_BALANCING_POLICY).isEqualTo("round_robin");
    }
}
