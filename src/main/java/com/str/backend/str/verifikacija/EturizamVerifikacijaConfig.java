package com.str.backend.str.verifikacija;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registrira {@link EturizamVerifikacijaProperties}; projekt nema {@code @ConfigurationPropertiesScan}. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EturizamVerifikacijaProperties.class)
public class EturizamVerifikacijaConfig {
}
