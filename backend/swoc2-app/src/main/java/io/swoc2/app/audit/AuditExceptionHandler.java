package io.swoc2.app.audit;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** problem+json for invalid audit queries (CLAUDE.md "Errors"). */
@RestControllerAdvice(assignableTypes = AuditController.class)
class AuditExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setType(URI.create("https://swoc2.example/problems/bad-request"));
        problem.setTitle("Bad request");
        return problem;
    }
}
