package com.platform.promotionservice.infrastructure.redis;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Lua scripts versioned trong {@code src/main/resources/lua}; bounded, trả
 * structured result codes (docs/promotion/development.md §6).
 */
@Component
public class LuaScripts {

    private final DefaultRedisScript<List> claimAcquire = load("lua/claim_acquire.lua");
    private final DefaultRedisScript<List> reserve = load("lua/reserve.lua");
    private final DefaultRedisScript<List> confirmPrepare = load("lua/confirm_prepare.lua");
    private final DefaultRedisScript<List> confirmFinalize = load("lua/confirm_finalize.lua");
    private final DefaultRedisScript<List> cancelRelease = load("lua/cancel_release.lua");

    public static DefaultRedisScript<List> load(String path) {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(List.class);
        return script;
    }

    public DefaultRedisScript<List> claimAcquire() {
        return claimAcquire;
    }

    public DefaultRedisScript<List> reserve() {
        return reserve;
    }

    public DefaultRedisScript<List> confirmPrepare() {
        return confirmPrepare;
    }

    public DefaultRedisScript<List> confirmFinalize() {
        return confirmFinalize;
    }

    public DefaultRedisScript<List> cancelRelease() {
        return cancelRelease;
    }
}
