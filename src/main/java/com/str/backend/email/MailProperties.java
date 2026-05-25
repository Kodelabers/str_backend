package com.str.backend.email;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.mail")
public record MailProperties(
        boolean enabled,
        String from,
        String loginUrl
) {
}
