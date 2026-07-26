package com.str.backend.egop;

import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;
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
}
