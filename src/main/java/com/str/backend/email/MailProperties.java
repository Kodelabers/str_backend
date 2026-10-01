package com.str.backend.email;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param redirectTo ako je postavljen, svaka poruka ide na tu adresu umjesto stvarnom primatelju,
 *                   a stvarni primatelj se upisuje u predmet. Za testne okoline nad stvarnim
 *                   podacima eTurizma. Prazna vrijednost znači da preusmjeravanja nema.
 */
@ConfigurationProperties("app.mail")
public record MailProperties(
        boolean enabled,
        String from,
        String loginUrl,
        String redirectTo
) {

    public MailProperties {
        redirectTo = redirectTo == null || redirectTo.isBlank() ? null : redirectTo.strip();
    }
}
