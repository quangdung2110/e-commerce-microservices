package com.ecommerce.inventory.command;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** HTTP form of a command's terminal result, original or replayed. */
public record CommandResponse(int status, JsonNode body, boolean problem) {

    public ResponseEntity<JsonNode> toEntity() {
        return ResponseEntity.status(status)
                .contentType(problem ? MediaType.APPLICATION_PROBLEM_JSON : MediaType.APPLICATION_JSON)
                .body(body);
    }
}
