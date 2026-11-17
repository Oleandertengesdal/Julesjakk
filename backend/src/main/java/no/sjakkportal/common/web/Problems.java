package no.sjakkportal.common.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import no.sjakkportal.common.error.ErrorCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

/**
 * Builds the one error shape the API returns: an RFC 9457 problem document extended with
 * {@code code}, {@code timestamp}, {@code traceId} and, for validation failures, {@code errors}.
 */
@Component
public class Problems {

    private final ObjectMapper objectMapper;

    public Problems(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ProblemDetail create(ErrorCode errorCode, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(errorCode.status(),
                detail == null || detail.isBlank() ? errorCode.defaultDetail() : detail);
        problem.setType(URI.create("urn:sjakkportal:error:" + errorCode.code()));
        problem.setTitle(errorCode.title());
        problem.setInstance(URI.create(request.getRequestURI().replaceAll("[^A-Za-z0-9/._~-]", "_")));
        problem.setProperty("code", errorCode.code());
        problem.setProperty("timestamp", Instant.now().toString());
        problem.setProperty("traceId", TraceIdFilter.current(request));
        return problem;
    }

    public ProblemDetail validation(List<Map<String, String>> fieldErrors, HttpServletRequest request) {
        ProblemDetail problem = create(ErrorCode.VALIDATION_FAILED, null, request);
        problem.setProperty("errors", fieldErrors);
        return problem;
    }

    /** For servlet filters and security handlers, which run outside Spring MVC's exception handling. */
    public void write(ErrorCode errorCode, HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(errorCode.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), create(errorCode, null, request));
    }
}
