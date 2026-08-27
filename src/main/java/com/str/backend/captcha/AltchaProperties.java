package com.str.backend.captcha;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.captcha")
public record AltchaProperties(
        boolean enabled,
        String hmacKey,
        int maxNumber,
        int expireSeconds
) {
}
