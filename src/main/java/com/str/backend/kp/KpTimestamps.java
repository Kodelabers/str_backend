package com.str.backend.kp;

import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeConstants;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.GregorianCalendar;

/**
 * Vremena u porukama KP-a. Sheme koriste tri oblika:
 * <ul>
 *   <li>{@code TimestampType} — {@code dd.MM.yyyy HH:mm:ss.SSS} (PS zaglavlje, upiti aktivnosti);</li>
 *   <li>{@code DatumVrijemeType} — {@code dd.MM.yyyy HH:mm:ss} (odgovor aktivnosti PS);</li>
 *   <li>{@code xs:dateTime} — FO zaglavlje i odgovori FO, te omotnica.</li>
 * </ul>
 * Prva dva nemaju zonu i čitaju se kao Europe/Zagreb; {@code xs:dateTime} bez zone također.
 * Zonu treba potvrditi s APIS-om (pitanje u {@code docs/KP-INTEGRACIJA.md}).
 */
public final class KpTimestamps {

    public static final ZoneId ZONE = ZoneId.of("Europe/Zagreb");

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss.SSS");
    private static final DateTimeFormatter DATUM_VRIJEME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");
    private static final DatatypeFactory DATATYPES;

    static {
        try {
            DATATYPES = DatatypeFactory.newInstance();
        } catch (DatatypeConfigurationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private KpTimestamps() {
    }

    /** {@code TimestampType}, lokalno vrijeme u Zagrebu. */
    public static String timestamp(Instant instant) {
        return TIMESTAMP.format(instant.atZone(ZONE));
    }

    /** {@code xs:dateTime} sa zagrebačkim pomakom i milisekundama. */
    public static XMLGregorianCalendar xml(Instant instant) {
        return DATATYPES.newXMLGregorianCalendar(GregorianCalendar.from(instant.atZone(ZONE)));
    }

    /**
     * {@code TimestampType} ili {@code DatumVrijemeType}; {@code null} za prazno ili neispravno —
     * vrijeme iz odgovora je podatak koji se zapisuje, ne razlog da se odgovor odbaci.
     */
    public static Instant parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.strip();
        try {
            return LocalDateTime.parse(v, v.length() > 19 ? TIMESTAMP : DATUM_VRIJEME).atZone(ZONE).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** {@code xs:dateTime}; bez zone se čita kao Zagreb. */
    public static Instant parse(XMLGregorianCalendar calendar) {
        if (calendar == null) {
            return null;
        }
        if (calendar.getTimezone() == DatatypeConstants.FIELD_UNDEFINED) {
            ZonedDateTime local = calendar.toGregorianCalendar().toZonedDateTime();
            return local.toLocalDateTime().atZone(ZONE).toInstant();
        }
        return calendar.toGregorianCalendar().toInstant();
    }
}
