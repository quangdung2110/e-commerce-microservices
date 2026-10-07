package com.ecommerce.checkout.problem;

import java.util.Map;

/** Short-circuits a pipeline with a {@link Problem}; rendered by the controller advice. */
public class ApiException extends RuntimeException {

    private final transient Problem problem;

    public ApiException(Problem problem) {
        super(problem.code().name(), null, false, false);
        this.problem = problem;
    }

    public ApiException(ProblemCode code, String detail) {
        this(Problem.of(code, detail));
    }

    public ApiException(ProblemCode code, String detail, Map<String, Object> extensions) {
        this(Problem.of(code, detail, extensions));
    }

    public Problem problem() {
        return problem;
    }
}
