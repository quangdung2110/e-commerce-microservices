package com.ecommerce.checkout.config;

import com.ecommerce.checkout.store.SessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

import java.time.Clock;

@Configuration
public class CheckoutConfig {

    private static final Logger log = LoggerFactory.getLogger(CheckoutConfig.class);

    /** One clock for every timestamp Checkout writes, replaceable in tests. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Loads the Lua scripts once at startup so the first request does not pay for it and readiness can
     * verify them. A failure is only logged: {@code /health/ready} retries the load and stays unready
     * until Redis is reachable.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void loadScripts(ApplicationReadyEvent event) {
        event.getApplicationContext().getBean(SessionStore.class).loadScripts()
                .subscribe(ignored -> { }, error -> log.warn("Lua scripts could not be loaded at startup", error),
                        () -> log.info("Lua scripts loaded"));
    }
}
