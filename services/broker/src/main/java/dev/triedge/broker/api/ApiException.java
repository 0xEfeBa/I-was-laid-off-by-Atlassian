package dev.triedge.broker.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class ApiException extends ErrorResponseException {

    private static final long serialVersionUID = 1L;

    public ApiException(HttpStatus status, String detail) {
        super(status, ProblemDetail.forStatusAndDetail(status, detail), null);
    }
}
