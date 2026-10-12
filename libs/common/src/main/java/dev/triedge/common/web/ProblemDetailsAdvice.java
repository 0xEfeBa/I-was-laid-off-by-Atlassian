package dev.triedge.common.web;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ProblemDetailsAdvice extends ResponseEntityExceptionHandler {

    static final String TRACE_ID = "trace_id";

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailsAdvice.class);

    private final Tracer tracer;

    public ProblemDetailsAdvice(Tracer tracer) {
        this.tracer = tracer;
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        log.error("Unhandled exception", ex);
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        return createResponseEntity(ProblemDetail.forStatus(status), new HttpHeaders(), status, request);
    }

    @Override
    protected ResponseEntity<Object> createResponseEntity(
            @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem) {
            Span span = tracer.currentSpan();
            if (span != null) {
                problem.setProperty(TRACE_ID, span.context().traceId());
            }
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }
}