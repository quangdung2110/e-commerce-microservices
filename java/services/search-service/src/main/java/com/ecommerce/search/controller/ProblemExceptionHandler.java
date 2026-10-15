package com.ecommerce.search.controller;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import com.ecommerce.search.common.ErrorCode;
import com.ecommerce.search.common.SearchException;
import com.ecommerce.search.config.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps every failure to RFC 9457 {@code application/problem+json} with the project
 * extensions {@code code}, {@code trace_id}, {@code retryable} and {@code errors[]}.
 *
 * <p>No legacy {@code error_code/message/details/request_id} body is produced anywhere
 * ({@code docs/search/domain.md §8}). The class extends {@link ResponseEntityExceptionHandler}
 * on purpose: a catch-all {@code Exception} handler alone swallows Spring's own protocol
 * errors — an unreadable body, an unknown route, a wrong method — and turns them into
 * {@code 500}.
 */
@RestControllerAdvice
public class ProblemExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemExceptionHandler.class);

    /** Wait advertised on a retryable 503; Search has no better estimate of when Elasticsearch recovers (contracts ADR-005). */
    static final int RETRY_AFTER_SECONDS = 2;

    @ExceptionHandler(SearchException.class)
    public ResponseEntity<Object> onSearchException(SearchException e, HttpServletRequest request) {
        if (e.code().status().is5xxServerError()) {
            log.error("request {} failed with {}", request.getRequestURI(), e.code(), e);
        }
        return problem(e.code(), e.getMessage(), e.errors(), request, new HttpHeaders());
    }

    /**
     * Elasticsearch failures. A timeout is {@code SEARCH_TIMEOUT}, an unreachable or
     * overloaded cluster is {@code ELASTICSEARCH_UNAVAILABLE}, and anything else the cluster
     * rejects is our own bug and stays {@code INTERNAL_ERROR}. None of them is evidence about
     * the catalog.
     */
    @ExceptionHandler({IOException.class, ElasticsearchException.class})
    public ResponseEntity<Object> onElasticsearchFailure(Exception e, HttpServletRequest request) {
        ErrorCode code = classify(e);
        log.error("Elasticsearch call for {} failed ({}): {}", request.getRequestURI(), code, e.getMessage());
        return problem(code, "the search backend is not available right now", List.of(), request, new HttpHeaders());
    }

    static ErrorCode classify(Exception e) {
        if (e instanceof ElasticsearchException es) {
            int status = es.status();
            if (status == 429 || status == 502 || status == 503) {
                return ErrorCode.ELASTICSEARCH_UNAVAILABLE;
            }
            if (status == 408 || status == 504) {
                return ErrorCode.SEARCH_TIMEOUT;
            }
            return ErrorCode.INTERNAL_ERROR;
        }
        boolean timedOut = e instanceof SocketTimeoutException
                || String.valueOf(e.getMessage()).toLowerCase().contains("timeout");
        return timedOut ? ErrorCode.SEARCH_TIMEOUT : ErrorCode.ELASTICSEARCH_UNAVAILABLE;
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> onUnexpected(Exception e, HttpServletRequest request) {
        log.error("unexpected failure on {}", request.getRequestURI(), e);
        return problem(ErrorCode.INTERNAL_ERROR, "unexpected internal error", List.of(), request, new HttpHeaders());
    }

    /**
     * Every protocol error Spring raises itself lands here with the status it chose; only the
     * body is replaced so the response keeps the Problem shape and any headers Spring set
     * (for example {@code Allow} on a {@code 405}).
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body,
                                                             HttpHeaders headers, HttpStatusCode statusCode,
                                                             WebRequest webRequest) {
        HttpServletRequest request = ((ServletWebRequest) webRequest).getRequest();
        ErrorCode code = protocolCode(statusCode);
        if (code.status().is5xxServerError()) {
            log.error("protocol failure on {}", request.getRequestURI(), ex);
        }
        return problem(code, detailOf(ex, code), errorsOf(ex), request, headers);
    }

    private static ErrorCode protocolCode(HttpStatusCode status) {
        return switch (status.value()) {
            case 404 -> ErrorCode.NOT_FOUND;
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
            case 406 -> ErrorCode.NOT_ACCEPTABLE;
            case 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
            case 400 -> ErrorCode.INVALID_QUERY_PARAMETER;
            default -> status.is4xxClientError() ? ErrorCode.INVALID_QUERY_PARAMETER : ErrorCode.INTERNAL_ERROR;
        };
    }

    /** Never echoes parser internals back to the caller. */
    private static String detailOf(Exception ex, ErrorCode code) {
        if (ex instanceof HttpMessageNotReadableException) {
            return "the request body is missing or is not valid JSON for this operation";
        }
        if (code == ErrorCode.INTERNAL_ERROR) {
            return "unexpected internal error";
        }
        return ex.getMessage();
    }

    private static List<SearchException.FieldError> errorsOf(Exception ex) {
        if (ex instanceof MissingServletRequestParameterException missing) {
            return List.of(new SearchException.FieldError(missing.getParameterName(), "is required"));
        }
        if (ex instanceof MethodArgumentTypeMismatchException mismatch) {
            return List.of(new SearchException.FieldError(mismatch.getName(), typeMessage(mismatch)));
        }
        if (ex instanceof TypeMismatchException mismatch && mismatch.getPropertyName() != null) {
            return List.of(new SearchException.FieldError(mismatch.getPropertyName(), typeMessage(mismatch)));
        }
        if (ex instanceof HttpMessageNotReadableException) {
            return List.of(new SearchException.FieldError("body", "must be a JSON object accepted by this operation"));
        }
        return List.of();
    }

    private static String typeMessage(TypeMismatchException mismatch) {
        return "must be of type " + (mismatch.getRequiredType() == null
                ? "the declared type" : mismatch.getRequiredType().getSimpleName());
    }

    private ResponseEntity<Object> problem(ErrorCode code, String detail,
                                           List<SearchException.FieldError> errors,
                                           HttpServletRequest request, HttpHeaders extraHeaders) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", code.typeUri());
        body.put("title", code.title());
        body.put("status", code.status().value());
        body.put("detail", detail == null || detail.isBlank() ? code.title() : truncate(detail, 1000));
        body.put("instance", request.getRequestURI());
        body.put("code", code.name());
        body.put("retryable", code.retryable());

        Object traceId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        if (traceId != null) {
            body.put("trace_id", traceId);
        }
        if (!errors.isEmpty()) {
            body.put("errors", errors.stream()
                    .map(error -> Map.of("field", error.field(), "message", truncate(error.message(), 500)))
                    .toList());
        }
        HttpStatus status = code.status();
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(extraHeaders);
        headers.remove(HttpHeaders.CONTENT_TYPE);
        if (status == HttpStatus.SERVICE_UNAVAILABLE && code.retryable() && !headers.containsKey(HttpHeaders.RETRY_AFTER)) {
            headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(RETRY_AFTER_SECONDS));
        }
        return ResponseEntity.status(status).headers(headers).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
