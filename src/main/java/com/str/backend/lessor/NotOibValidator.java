package com.str.backend.lessor;

import com.str.backend.common.Oib;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.Locale;
import java.util.regex.Pattern;

public class NotOibValidator implements ConstraintValidator<NotOib, String> {

    /**
     * Razdjelnici koje ljudi upisuju u brojeve: praznine (i tvrdi razmak iz kopiranog teksta),
     * crtice, točke, kose crte.
     */
    private static final Pattern SEPARATORS =
            Pattern.compile("[\\s\\-./]+", Pattern.UNICODE_CHARACTER_CLASS);

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        return !Oib.isValid(normalize(value));
    }

    /**
     * OIB kako ga ljudi pišu: „399 865 406 78", „399-865-406-78" i „HR39986540678" (PDV
     * identifikator) su isti OIB. Isto pravilo ima ulazni korak na frontendu ({@code utils/oib.ts}).
     */
    static String normalize(String value) {
        String compact = SEPARATORS.matcher(value).replaceAll("");
        return compact.toUpperCase(Locale.ROOT).startsWith("HR") ? compact.substring(2) : compact;
    }
}
