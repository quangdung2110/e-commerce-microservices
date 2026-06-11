package com.platform.productservice.infrastructure.redis.script;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads and manages Lua scripts from classpath.
 * Logs SHA hashes for EVALSHA caching support.
 */
@Component
public class LuaScriptLoader {

    private static final Logger log = LoggerFactory.getLogger(LuaScriptLoader.class);

    private final Map<String, DefaultRedisScript<Long>> scripts = new HashMap<>();
    private final Map<String, String> scriptShas = new HashMap<>();

    public static final String INVENTORY_FAST_SYNC = "inventory_fast_sync";
    public static final String DISTRIBUTED_LOCK = "distributed_lock";

    @PostConstruct
    public void loadScripts() {
        loadScript(INVENTORY_FAST_SYNC, "lua/inventory_fast_sync.lua");
        loadScript(DISTRIBUTED_LOCK, "lua/distributed_lock.lua");
        log.info("Lua scripts loaded: {} scripts registered", scripts.size());
    }

    private void loadScript(String name, String classpath) {
        try {
            Resource resource = new PathMatchingResourcePatternResolver()
                    .getResource("classpath:" + classpath);
            String scriptContent = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            DefaultRedisScript<Long> script = new DefaultRedisScript<>();
            script.setScriptText(scriptContent);
            script.setResultType(Long.class);
            scripts.put(name, script);
            log.info("Loaded Lua script: name={}, path={}", name, classpath);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load Lua script: " + name, e);
        }
    }

    /**
     * Returns a loaded script by name.
     */
    public DefaultRedisScript<Long> getScript(String name) {
        DefaultRedisScript<Long> script = scripts.get(name);
        if (script == null) {
            throw new IllegalArgumentException("Unknown Lua script: " + name);
        }
        return script;
    }

    /**
     * Returns all loaded script names.
     */
    public List<String> getScriptNames() {
        return List.copyOf(scripts.keySet());
    }

    /**
     * Returns the SHA hash of a loaded script (after it has been loaded via SCRIPT LOAD).
     */
    public String getScriptSha(String name) {
        return scriptShas.get(name);
    }

    /**
     * Stores the SHA hash for a script (set after SCRIPT LOAD is called by Redis).
     */
    public void setScriptSha(String name, String sha) {
        scriptShas.put(name, sha);
        log.info("Script SHA registered: name={}, sha={}", name, sha);
    }
}
