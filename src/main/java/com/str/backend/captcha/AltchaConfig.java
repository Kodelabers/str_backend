package com.str.backend.captcha;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AltchaProperties.class)
public class AltchaConfig {

    @Bean
    public AltchaService altchaService(AltchaProperties properties, ObjectMapper mapper) {
        return new AltchaService(properties, mapper);
    }
}
