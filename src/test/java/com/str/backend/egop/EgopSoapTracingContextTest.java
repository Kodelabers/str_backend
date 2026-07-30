package com.str.backend.egop;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kad je {@code str.egop.trace-soap=true}, kontekst se mora dići i bean aktivirati — potvrđuje da
 * {@code LoggingSystem} injektira (inače bi go-live trace-run pao na startu, a to bi se otkrilo
 * tek na CDU).
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "str.egop.trace-soap=true")
class EgopSoapTracingContextTest {

    @Autowired(required = false)
    private EgopSoapTracing egopSoapTracing;

    @Test
    void beanActivatesWhenToggleOn() {
        assertThat(egopSoapTracing).isNotNull();
    }
}
