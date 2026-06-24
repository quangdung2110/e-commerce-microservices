package com.platform.inventoryservice.infrastructure.redis.script;

import jakarta.annotation.PostConstruct;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class LuaScriptRegistry {

    private final LuaScriptLoader loader;

    private DefaultRedisScript<List> reserveScript;
    private DefaultRedisScript<List> releaseScript;
    private DefaultRedisScript<List> deductScript;

    public LuaScriptRegistry(LuaScriptLoader loader) {
        this.loader = loader;
    }

    @PostConstruct
    void init() {
        reserveScript = load("lua/reserve_inventory.lua");
        releaseScript = load("lua/release_inventory.lua");
        deductScript  = load("lua/deduct_inventory.lua");
    }

    @SuppressWarnings("rawtypes")
    private DefaultRedisScript<List> load(String path) {
        String body = loader.load(path);
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setScriptText(body);
        script.setResultType(List.class);
        return script;
    }

    public DefaultRedisScript<List> reserve() { return reserveScript; }
    public DefaultRedisScript<List> release() { return releaseScript; }
    public DefaultRedisScript<List> deduct()  { return deductScript; }
}
