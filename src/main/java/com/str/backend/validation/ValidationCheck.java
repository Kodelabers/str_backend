package com.str.backend.validation;

import java.util.Set;

public interface ValidationCheck {

    String step();

    int order();

    /**
     * Steps this check must run after. Used by {@link ParallelValidationOrchestrator}
     * to schedule waves: wave N contains checks whose dependencies completed in wave &lt; N.
     * Default: no dependencies (independent / first wave).
     */
    default Set<String> dependsOn() { return Set.of(); }

    ValidationResult check(ValidationContext context);
}
