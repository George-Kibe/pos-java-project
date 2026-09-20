package com.pos.gateway.api;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pos.common.correlation.CorrelationId;

/**
 * What a caller gets when a downstream service is unreachable or its circuit is open.
 *
 * <p>Without this, a dead service surfaces as a hung request that eventually times out somewhere up
 * the stack - the worst possible failure for a till, which needs to know immediately that it cannot
 * reach the server so it can fall back to offline mode.
 */
@RestController
public class FallbackController {

    @RequestMapping("/fallback/service-unavailable")
    public ResponseEntity<ProblemDetail> serviceUnavailable() {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "That service is temporarily unavailable. Please try again shortly.");
        problem.setType(URI.create("https://docs.pos.local/problems/service.unavailable"));
        problem.setTitle(HttpStatus.SERVICE_UNAVAILABLE.getReasonPhrase());
        problem.setProperty("code", "service.unavailable");
        problem.setProperty("correlationId", CorrelationId.get());

        // Signals a transient condition, so a client may retry rather than treating it as fatal.
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "5")
                .body(problem);
    }
}
