package com.str.backend.egop;

import org.junit.jupiter.api.Test;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class EgopSoapTracingTest {

    /**
     * Imena Spring WS trace loggera su točne magične konstante — tipfeler bi tiho onemogućio
     * trace bez ijedne greške. Ovaj test ih zaključava.
     */
    @Test
    void enable_raisesClientMessageTracingLoggersToTrace() {
        LoggingSystem loggingSystem = mock(LoggingSystem.class);

        new EgopSoapTracing(loggingSystem).enable();

        verify(loggingSystem).setLogLevel("org.springframework.ws.client.MessageTracing.sent", LogLevel.TRACE);
        verify(loggingSystem).setLogLevel("org.springframework.ws.client.MessageTracing.received", LogLevel.TRACE);
    }
}
