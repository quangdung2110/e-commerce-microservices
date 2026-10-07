package com.ecommerce.search.controller;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import com.ecommerce.search.common.ErrorCode;
import com.ecommerce.search.common.SearchException;
import com.ecommerce.search.config.SearchProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps every failure to RFC 9457 {@code application/problem+json} with the project
 * extensions {@code code}, {@code trace_id}, {@code retryable} and {@code errors[]}.
 *
 * <p>No legacy {@code error_code/message/details/request_id} body is produced anywhere
 * ({@code docs/search/domain.md §8}).
 */
@RestControllerAdvice
public class ProblemExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemExceptionHandler.class);

    private final SearchProperties properties;

    public ProblemExceptionHandler(SearchProperties properties) {
        this.properties = properties;
    }

    @ExceptionHandler(SearchException.class)
    public ResponseEntity<Map<String, Object>> onSearchException(SearchException e, HttpServletRequest request) {
        if (e.code().status().is5xxServerError()) {
            log.error("request {} failed with {}", request.getRequestURI(), e.code(), e);
        }
        return problem(e.code(), e.getMessage(), e.errors(), request);
    }

    /** A parameter of the wrong type never reaches the service layer. */
    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class,
            MethodArgumentNotValidException.class})
    public ResponseEntity<Map<String, Object>> onBadRequest(Exception e, HttpServletRequest request) {
        return problem(ErrorCode.INVALID_QUERY_PARAMETER, e.getMessage(), List.of(), request);
    }

    /**
     * Elasticsearch failures. A timeout is reported as {@code SEARCH_TIMEOUT} and anything
     * else as {@code ELASTICSEARCH_UNAVAILABLE} — both are infrastructure problems and
     * neither is evidence about the catalog.
     */
    @ExceptionHandler({IOException.class, ElasticsearchException.class})
    public ResponseEntity<Map<String, Object>> onElasticsearchFailure(Exception e, HttpServletRequest request) {
        boolean timedOut = e instanceof SocketTimeoutException
                || String.valueOf(e.getMessage()).toLowerCase().contains("timeout");
        ErrorCode code = timedOut ? ErrorCode.SEARCH_TIMEOUT
                : (e instanceof ConnectException || e instanceof IOException
                    ? ErrorCode.ELASTICSEARCH_UNAVAILABLE : ErrorCode.INTERNAL_ERROR);
        log.error("Elasticsearch call for {} failed: {}", request.getRequestURI(), e.getMessage());
        return problem(code, "the search backend is not available right now", List.of(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> onUnexpected(Exception e, HttpServletRequest request) {
        log.error("unexpected failure on {}", request.getRequestURI(), e);
        return problem(ErrorCode.INTERNAL_ERROR, "unexpected internal error", List.of(), request);
    }

    private ResponseEntity<Map<String, Object>> problem(ErrorCode code, String detail,
                                                        List<SearchException.FieldError> errors,
                                                        HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", code.typeUri());
        body.put("title", code.title());
        body.put("status", code.status().value());
        body.put("detail", detail == null || detail.isBlank() ? code.title() : detail);
        body.put("instance", request.getRequestURI());
        body.put("code", code.name());
        body.put("retryable", code.retryable());

        String traceId = request.getHeader(properties.gateway().requestIdHeader());
        if (traceId != null && !traceId.isBlank()) {
            body.put("trace_id", traceId);
        }
        if (!errors.isEmpty()) {
            body.put("errors", errors.stream()
                    .map(error -> Map.of("field", error.field(), "message", error.message()))
                    .toList());
        }
        HttpStatus status = code.status();
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }
}
