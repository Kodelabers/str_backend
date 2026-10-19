package com.str.backend.egop;

import org.junit.jupiter.api.Test;

import javax.xml.datatype.DatatypeFactory;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class EgopDatesTest {

    /** .NET DateTime bez zone je lokalno vrijeme eGOP-a (Zagreb), ne zona JVM-a. */
    @Test
    void toInstant_withoutZone_isZagrebTime() throws Exception {
        var ljeto = DatatypeFactory.newInstance().newXMLGregorianCalendar("2026-07-01T10:00:00");
        var zima = DatatypeFactory.newInstance().newXMLGregorianCalendar("2026-12-01T10:00:00");

        assertEquals(Instant.parse("2026-07-01T08:00:00Z"), EgopDates.toInstant(ljeto));
        assertEquals(Instant.parse("2026-12-01T09:00:00Z"), EgopDates.toInstant(zima));
    }

    @Test
    void toInstant_withZone_keepsGivenZone() throws Exception {
        var utc = DatatypeFactory.newInstance().newXMLGregorianCalendar("2026-07-01T10:00:00Z");

        assertEquals(Instant.parse("2026-07-01T10:00:00Z"), EgopDates.toInstant(utc));
    }

    /** DateTime.MinValue znači „nema vrijednosti" za polja koja u WSDL-u nisu nillable. */
    @Test
    void toInstant_dotNetMinValue_isNull() throws Exception {
        var min = DatatypeFactory.newInstance().newXMLGregorianCalendar("0001-01-01T00:00:00");

        assertNull(EgopDates.toInstant(min));
        assertNull(EgopDates.toInstant(null));
    }
}
