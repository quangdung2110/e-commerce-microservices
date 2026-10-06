package com.ecommerce.review.controller;

import com.ecommerce.review.config.CallerResolver;
import com.ecommerce.review.dto.CommandDtos;
import com.ecommerce.review.service.MediaAssetService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one media endpoint a client calls.
 *
 * <p>It hands back an {@code asset_id} and a signed upload instruction, never the object
 * key. There is no verify or poll endpoint: the client uploads and then sends the
 * {@code asset_id} with the review, and Review checks the object itself before attaching
 * it (ADR-011).
 */
@RestController
@RequestMapping("/api/v1/reviews/media")
public class ReviewMediaController {

    private final MediaAssetService mediaAssets;
    private final CallerResolver callers;

    public ReviewMediaController(MediaAssetService mediaAssets, CallerResolver callers) {
        this.mediaAssets = mediaAssets;
        this.callers = callers;
    }

    @PostMapping("/upload-urls")
    @ResponseStatus(HttpStatus.CREATED)
    public CommandDtos.IssuedMediaAsset issueUploadUrl(
            HttpServletRequest request,
            @Valid @RequestBody CommandDtos.IssueMediaUploadRequest body) {
        return mediaAssets.issueUploadInstruction(callers.requireUserId(request), body);
    }
}
