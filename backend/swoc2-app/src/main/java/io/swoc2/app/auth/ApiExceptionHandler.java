package io.swoc2.app.auth;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * REST errors as {@code application/problem+json} (RFC 9457), never a stack trace
 * (CLAUDE.md "Errors"). An {@link AccessDeniedException} from a failed {@code @PreAuthorize}
 * check is what a role-protected endpoint throws for the wrong role.
 */
@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail handleAccessDenied(AccessDeniedException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setType(URI.create("https://swoc2.example/problems/forbidden"));
        problem.setTitle("Forbidden");
        problem.setDetail("You do not have the required role for this action.");
        return problem;
    }
}
