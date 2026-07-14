package com.str.backend.auth.nias;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("nias.saml")
public record NiasSamlProperties(
        boolean enabled,
        String entityId,
        String metadataUri,
        String acsUrl,
        String sloUrl,
        String keystorePath,
        String keystorePassword,
        String keyAlias,
        String successRedirectUrl,
        String failureRedirectUrl
) {}
