package no.sjakkportal.common.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import no.sjakkportal.common.error.ChessException;
import no.sjakkportal.common.error.ErrorCode;
import no.sjakkportal.common.error.RateLimitedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpMediaTypeException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Every exception leaves the API through here as a cataloged problem document.
 * Framework messages are never passed on: they describe internals, and the catalog text is
 * what the user should read.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final Problems problems;

    public GlobalExceptionHandler(Problems problems) {
        this.problems = problems;
    }

    @ExceptionHandler(RateLimitedException.class)
    ResponseEntity<ProblemDetail> rateLimited(RateLimitedException e, HttpServletRequest request) {
        return ResponseEntity.status(ErrorCode.RATE_LIMITED.status())
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds()))
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problems.create(ErrorCode.RATE_LIMITED, null, request));
    }

    @ExceptionHandler(ChessException.class)
    ResponseEntity<ProblemDetail> chess(ChessException e, HttpServletRequest request) {
        return respond(e.errorCode(), e.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> invalidBody(MethodArgumentNotValidException e, HttpServletRequest request) {
        List<Map<String, String>> errors = new ArrayList<>();
        e.getBindingResult().getFieldErrors().forEach(error -> errors.add(Map.of(
                "field", error.getField(),
                "message", error.getDefaultMessage() == null ? "ugyldig" : error.getDefaultMessage())));
        return body(ErrorCode.VALIDATION_FAILED, problems.validation(errors, request));
    }

    @ExceptionHandler({HandlerMethodValidationException.class, ConstraintViolationException.class})
    ResponseEntity<ProblemDetail> invalidParameter(Exception e, HttpServletRequest request) {
        return body(ErrorCode.VALIDATION_FAILED, problems.validation(List.of(), request));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, HttpMediaTypeException.class,
            MethodArgumentTypeMismatchException.class, ServletRequestBindingException.class})
    ResponseEntity<ProblemDetail> malformed(Exception e, HttpServletRequest request) {
        return respond(ErrorCode.MALFORMED_REQUEST, null, request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ProblemDetail> methodNotAllowed(Exception e, HttpServletRequest request) {
        return respond(ErrorCode.METHOD_NOT_ALLOWED, null, request);
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    ResponseEntity<ProblemDetail> notFound(Exception e, HttpServletRequest request) {
        return respond(ErrorCode.NOT_FOUND, null, request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> accessDenied(Exception e, HttpServletRequest request) {
        return respond(ErrorCode.AUTH_ACCESS_DENIED, null, request);
    }

    @ExceptionHandler({OptimisticLockingFailureException.class, PessimisticLockingFailureException.class})
    ResponseEntity<ProblemDetail> concurrentUpdate(Exception e, HttpServletRequest request) {
        return respond(ErrorCode.CONFLICT, null, request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> integrity(DataIntegrityViolationException e, HttpServletRequest request) {
        // A constraint caught something the service layer should have: log it so the gap gets closed.
        log.warn("Constraint violation reached the API boundary [traceId={}]", TraceIdFilter.current(request), e);
        return respond(ErrorCode.CONFLICT, null, request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e, HttpServletRequest request) {
        log.error("Unhandled exception [traceId={}] {} {}", TraceIdFilter.current(request), request.getMethod(),
                request.getRequestURI(), e);
        return respond(ErrorCode.INTERNAL_ERROR, null, request);
    }

    private ResponseEntity<ProblemDetail> respond(ErrorCode code, String detail, HttpServletRequest request) {
        return body(code, problems.create(code, detail, request));
    }

    private static ResponseEntity<ProblemDetail> body(ErrorCode code, ProblemDetail problem) {
        return ResponseEntity.status(code.status()).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }
}
