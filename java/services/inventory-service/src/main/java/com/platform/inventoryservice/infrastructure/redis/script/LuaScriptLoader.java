package com.platform.inventoryservice.infrastructure.redis.script;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class LuaScriptLoader {

    private final ResourcePatternResolver resolver;

    public LuaScriptLoader(ResourcePatternResolver resolver) {
        this.resolver = resolver;
    }

    public String load(String classpathPath) {
        try {
            Resource resource = resolver.getResource("classpath:" + classpathPath);
            try (var in = resource.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load Lua script: " + classpathPath, e);
        }
    }
}
