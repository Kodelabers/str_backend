package com.str.backend.validation;

import lombok.AllArgsConstructor;
import lombok.Getter;

public sealed interface ValidationResult {

    @Getter
    @AllArgsConstructor
    final class Passed implements ValidationResult {
        private String step;
        private String detail;
    }

    @Getter
    @AllArgsConstructor
    final class Rejected implements ValidationResult {
        private String step;
        private String reason;
    }
}
