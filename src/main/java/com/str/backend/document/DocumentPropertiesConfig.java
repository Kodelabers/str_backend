package com.str.backend.document;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registrira {@link DocumentProperties}; projekt nema {@code @ConfigurationPropertiesScan}. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DocumentProperties.class)
public class DocumentPropertiesConfig {
}
