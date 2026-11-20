package com.platform.productservice.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ObservabilityRoutingTest {
    @RestController
    static class MissingResource {
        @GetMapping("/routing-probe")
        String read() throws NoResourceFoundException {
            throw new NoResourceFoundException(HttpMethod.GET, "/routing-probe", "missing");
        }
    }

    @Test
    void missingResourceIsNotCountedAsServerFailure() throws Exception {
        MockMvcBuilders.standaloneSetup(new MissingResource()).setControllerAdvice(new GlobalExceptionHandler())
                .build().perform(get("/routing-probe")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test
    void unsupportedMethodPreservesAllowAndDoesNotBecome500() throws Exception {
        MockMvcBuilders.standaloneSetup(new MissingResource()).setControllerAdvice(new GlobalExceptionHandler())
                .build().perform(post("/routing-probe")).andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "GET"));
    }
}
