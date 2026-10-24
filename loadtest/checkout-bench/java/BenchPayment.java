package bench;

import com.platform.paymentservice.PaymentServiceApplication;
import com.stripe.StripeClient;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/** Test-only launcher: redirects the real Stripe SDK to the isolated HTTP simulator. */
public class BenchPayment {
    public static void main(String[] args) {
        if (!"http://provider:8080".equals(System.getenv("BENCH_STRIPE_BASE"))) {
            throw new IllegalStateException("isolated bench provider required");
        }
        new SpringApplicationBuilder(PaymentServiceApplication.class, ProviderConfig.class).run(args);
    }

    @Configuration(proxyBeanMethods = false)
    public static class ProviderConfig {
        @Bean
        @Primary
        StripeClient benchmarkStripeClient() {
            return StripeClient.builder().setApiKey("sk_test_isolated_benchmark")
                    .setApiBase("http://provider:8080").build();
        }
    }
}
