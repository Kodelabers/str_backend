package com.str.backend.egop;

import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeConstants;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.GregorianCalendar;

/**
 * Konverzije {@link LocalDateTime} ↔ {@link XMLGregorianCalendar} — CXF za
 * xs:dateTime generira XMLGregorianCalendar, a domenski kod radi s java.time.
 */
public final class EgopDates {

    private static final DatatypeFactory FACTORY;

    static {
        try {
            FACTORY = DatatypeFactory.newInstance();
        } catch (DatatypeConfigurationException e) {
            throw new IllegalStateException("DatatypeFactory unavailable", e);
        }
    }

    private EgopDates() {
    }

    public static XMLGregorianCalendar toXml(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        GregorianCalendar calendar = GregorianCalendar.from(dateTime.atZone(ZoneId.systemDefault()));
        return FACTORY.newXMLGregorianCalendar(calendar);
    }

    public static LocalDateTime fromXml(XMLGregorianCalendar calendar) {
        if (calendar == null) {
            return null;
        }
        return calendar.toGregorianCalendar().toZonedDateTime().toLocalDateTime();
    }

    /** Zona eGOP-a (MINT); vrijedi za vrijednosti koje stignu bez oznake zone. */
    private static final ZoneId EGOP_ZONE = ZoneId.of("Europe/Zagreb");

    /**
     * Trenutak iz eGOP-ova {@code xs:dateTime}.
     *
     * <ul>
     *   <li>Bez oznake zone (uobičajeno za .NET {@code DateTime}) čita se kao vrijeme u Zagrebu,
     *       ne u zoni JVM-a — u kontejneru je to obično UTC, pa bi vrijeme odstupalo sat-dva.</li>
     *   <li>{@code 0001-01-01} je .NET {@code DateTime.MinValue}, koji eGOP vraća za
     *       ne-nillable polje bez vrijednosti, pa je {@code null}.</li>
     * </ul>
     */
    public static Instant toInstant(XMLGregorianCalendar calendar) {
        if (calendar == null || calendar.getYear() <= 1) {
            return null;
        }
        if (calendar.getTimezone() == DatatypeConstants.FIELD_UNDEFINED) {
            return calendar.toGregorianCalendar().toZonedDateTime().toLocalDateTime()
                    .atZone(EGOP_ZONE).toInstant();
        }
        return calendar.toGregorianCalendar().toInstant();
    }
}
