package no.sjakkportal.common.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A public, database-free liveness check under /api, so it travels through the same proxy as the
 * rest of the API. The browser polls it while a sleeping server starts (see docs/DEPLOYMENT.md).
 */
@RestController
public class PingController {

    @GetMapping("/api/ping")
    ResponseEntity<Void> ping() {
        return ResponseEntity.noContent().build();
    }
}
