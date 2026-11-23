package com.seatlock.assistant.api;

import com.seatlock.assistant.refund.RefundAssistantService;
import com.seatlock.common.config.OpenApiConfig;
import com.seatlock.common.ratelimit.RateLimited;
import com.seatlock.identity.security.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Ask about cancelling your booking; the answer never changes anything (404 for bookings that aren't yours). */
@Tag(name = "Refund Assistant", description = "Explains the cancellation policy for one booking (engine facts + RAG)")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@RestController
public class RefundAssistantController {

    private final RefundAssistantService assistant;

    public RefundAssistantController(RefundAssistantService assistant) {
        this.assistant = assistant;
    }

    @Operation(summary = "Answer a cancellation question for my booking: AI explanation or template, with policy excerpts")
    @RateLimited("ai")
    @PostMapping("/api/v1/bookings/{publicId}/assistant")
    public ResponseEntity<RefundAssistantService.Answer> ask(@CurrentUserId long userId, @PathVariable String publicId,
                                                             @Valid @RequestBody RefundQuestionRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(assistant.ask(userId, publicId, request.question()));
    }
}
