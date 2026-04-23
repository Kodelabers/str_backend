package com.str.backend.validation;

public sealed interface ValidacijskiRezultat {

    record Prosla(String step, String detail) implements ValidacijskiRezultat {}

    record Odbijena(String step, String razlog) implements ValidacijskiRezultat {}
}
