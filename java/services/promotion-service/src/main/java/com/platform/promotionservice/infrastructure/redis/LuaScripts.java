package com.platform.promotionservice.infrastructure.redis;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
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

    /**
     * Đọc script đúng một lần lúc khởi động. Không dùng {@code setLocation(...)}: script dựa trên Resource gọi
     * {@code isModified()} (stat file/jar) bên trong khối {@code synchronized} toàn cục ở mỗi lần thực thi, nên mọi
     * request claim xếp hàng sau một I/O — đo được 14,9 s so với 0,15 s cho 1000 claim đồng thời.
     */
    public static DefaultRedisScript<List> load(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new DefaultRedisScript<>(new String(in.readAllBytes(), StandardCharsets.UTF_8), List.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load Lua script " + path, e);
        }
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
