package com.str.backend.kp;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registrira {@link KpProperties} <b>bezuvjetno</b> — {@link KpConfig} se diže samo uz uključen KP,
 * a {@link KpOtpremaService} postoji uvijek (zove ga tok urudžbe) i čita propertyje u oba slučaja.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KpProperties.class)
public class KpPropertiesConfig {
}
