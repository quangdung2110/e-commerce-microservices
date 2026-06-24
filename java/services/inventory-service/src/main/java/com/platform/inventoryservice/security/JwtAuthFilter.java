package com.platform.inventoryservice.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthFilter extends OncePerRequestFilter {

    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        try {
            String jwt = parseJwt(request);
            if (jwt != null) {
                // Manually parse JWT payload
                String[] chunks = jwt.split("\\.");
                if (chunks.length == 3) {
                    String payload = new String(Base64.getUrlDecoder().decode(chunks[1]));
                    Map<String, Object> claims = objectMapper.readValue(payload, new TypeReference<>() {});

                    String username = (String) claims.get("sub");
                    Collection<GrantedAuthority> authorities = extractAuthorities(claims);

                    UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                        username != null ? username : "anonymous",
                        null,
                        authorities
                    );
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            }
        } catch (Exception e) {
            log.error("Cannot set user authentication: {}", e.getMessage());
        }

        filterChain.doFilter(request, response);
    }

    private String parseJwt(HttpServletRequest request) {
        String headerAuth = request.getHeader("Authorization");
        if (StringUtils.hasText(headerAuth) && headerAuth.startsWith("Bearer ")) {
            return headerAuth.substring(7);
        }
        return null;
    }

    private Collection<GrantedAuthority> extractAuthorities(Map<String, Object> claims) {
        Collection<GrantedAuthority> authorities = new ArrayList<>();

        Object scopeClaim = claims.get("scope");
        if (scopeClaim instanceof String scopeStr) {
            for (String scope : scopeStr.split(" ")) {
                authorities.add(new SimpleGrantedAuthority("SCOPE_" + scope));
            }
        } else if (scopeClaim instanceof List<?> scopeList) {
            for (Object scope : scopeList) {
                authorities.add(new SimpleGrantedAuthority("SCOPE_" + scope.toString()));
            }
        }

        Object roleClaim = claims.get("role");
        if (roleClaim instanceof String roleStr) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + roleStr));
        }

        return authorities;
    }
}
