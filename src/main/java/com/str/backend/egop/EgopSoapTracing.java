package com.str.backend.egop;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.stereotype.Component;

/**
 * Dijagnostički prekidač {@code EGOP_TRACE_SOAP=true}: diže Spring WS klijentske
 * {@code MessageTracing} loggere na TRACE, pa se u log ispisuje <b>puni SOAP zahtjev i
 * odgovor</b> (envelope) za svaki eGOP poziv. Bez ovoga {@code egop_call} log daje samo
 * operaciju, trajanje i eGOP kod+poruku — dovoljno za 401/nadležnost/šifru, ali ne i sirovi XML.
 *
 * <p>Bean postoji samo kad je zastavica upaljena, pa je učinak lokaliziran. Namijenjeno
 * dijagnostici prvog živog testa i gasi se poslije: trace ispisuje osobne podatke stranke
 * (OIB, ime, adresa) u cijelosti, a poziv {@code KreirajDokumentZaPismeno} usput dumpa i cijeli
 * base64 PDF u log.
 */
@Component
@ConditionalOnProperty(name = "str.egop.trace-soap", havingValue = "true")
class EgopSoapTracing {

    private static final Logger log = LoggerFactory.getLogger(EgopSoapTracing.class);

    static final String SENT_LOGGER = "org.springframework.ws.client.MessageTracing.sent";
    static final String RECEIVED_LOGGER = "org.springframework.ws.client.MessageTracing.received";

    private final LoggingSystem loggingSystem;

    EgopSoapTracing(LoggingSystem loggingSystem) {
        this.loggingSystem = loggingSystem;
    }

    @PostConstruct
    void enable() {
        loggingSystem.setLogLevel(SENT_LOGGER, LogLevel.TRACE);
        loggingSystem.setLogLevel(RECEIVED_LOGGER, LogLevel.TRACE);
        log.warn("egop_soap_trace ENABLED — pun SOAP zahtjev/odgovor u logu (sadrži OIB/ime/adresu,"
                + " a KreirajDokumentZaPismeno i cijeli base64 PDF); ugasiti EGOP_TRACE_SOAP nakon dijagnostike");
    }
}
