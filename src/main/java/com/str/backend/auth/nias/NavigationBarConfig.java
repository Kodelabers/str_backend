package com.str.backend.auth.nias;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registrira {@link NavigationBarProperties}; projekt nema {@code @ConfigurationPropertiesScan}. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NavigationBarProperties.class)
public class NavigationBarConfig {
}
