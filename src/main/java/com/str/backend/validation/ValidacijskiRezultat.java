package com.str.backend.validation;

import lombok.AllArgsConstructor;
import lombok.Getter;

public sealed interface ValidacijskiRezultat {

    @Getter
    @AllArgsConstructor
    final class Prosla implements ValidacijskiRezultat {
        private String step;
        private String detail;
    }

    @Getter
    @AllArgsConstructor
    final class Odbijena implements ValidacijskiRezultat {
        private String step;
        private String razlog;
    }
}
