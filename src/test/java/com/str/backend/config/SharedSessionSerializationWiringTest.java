package com.str.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.convert.ConversionService;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bean {@code springSessionConversionService} vrijedi samo ako ga Spring Session JDBC stvarno uzme
 * (po imenu). Inače bi postojao, a sesije bi se i dalje čitale Springovim čitačem, bez tolerancije.
 */
@SpringBootTest
@ActiveProfiles("test")
class SharedSessionSerializationWiringTest {

    @Autowired
    private JdbcIndexedSessionRepository sessionRepository;

    @Autowired
    @Qualifier("springSessionConversionService")
    private ConversionService ours;

    @Test
    void springSessionUsesOurConversionService() {
        assertThat(ReflectionTestUtils.getField(sessionRepository, "conversionService")).isSameAs(ours);
    }
}
