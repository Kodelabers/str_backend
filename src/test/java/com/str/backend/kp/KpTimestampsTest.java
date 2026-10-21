package com.str.backend.kp;

import org.junit.jupiter.api.Test;

import javax.xml.datatype.DatatypeFactory;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class KpTimestampsTest {

    @Test
    void timestamp_isZagrebLocalTimeWithMillis() {
        assertEquals("01.07.2026 10:00:00.123", KpTimestamps.timestamp(Instant.parse("2026-07-01T08:00:00.123Z")));
        assertEquals("01.12.2026 10:00:00.000", KpTimestamps.timestamp(Instant.parse("2026-12-01T09:00:00Z")));
    }

    @Test
    void parse_timestampAndDatumVrijeme_asZagrebTime() {
        assertEquals(Instant.parse("2026-07-01T08:00:00.123Z"), KpTimestamps.parse("01.07.2026 10:00:00.123"));
        assertEquals(Instant.parse("2026-12-01T09:00:00Z"), KpTimestamps.parse("01.12.2026 10:00:00"));
    }

    /** Vrijeme iz odgovora je podatak za zapis — neispravno se ne pretvara u pad odgovora. */
    @Test
    void parse_blankOrMalformed_isNull() {
        assertNull(KpTimestamps.parse((String) null));
        assertNull(KpTimestamps.parse(" "));
        assertNull(KpTimestamps.parse("2026-07-01"));
    }

    @Test
    void xmlDateTime_withoutZone_isZagrebTime_withZone_keepsIt() throws Exception {
        DatatypeFactory f = DatatypeFactory.newInstance();
        assertEquals(Instant.parse("2026-07-01T08:00:00Z"),
                KpTimestamps.parse(f.newXMLGregorianCalendar("2026-07-01T10:00:00")));
        assertEquals(Instant.parse("2026-07-01T10:00:00Z"),
                KpTimestamps.parse(f.newXMLGregorianCalendar("2026-07-01T10:00:00Z")));
        assertNull(KpTimestamps.parse((javax.xml.datatype.XMLGregorianCalendar) null));
    }

    @Test
    void xml_roundTrips() {
        Instant now = Instant.parse("2026-10-21T08:15:30.250Z");
        assertEquals(now, KpTimestamps.parse(KpTimestamps.xml(now)));
    }
}
