package com.str.backend.egop;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registrira {@link EgopProperties} i {@link EgopVrsteProperties} <b>bezuvjetno</b> —
 * {@link EgopConfig} to ne može jer se diže samo uz {@code enabled=true}, a
 * {@link EgopFilingService} radi i s mock klijentom pa mu propertyji trebaju u oba slučaja.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({EgopProperties.class, EgopVrsteProperties.class})
public class EgopPropertiesConfig {
}
