package com.str.backend.objection;

import com.str.backend.domain.RnStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Red popisa očitovanja i prigovora preko svih RB-ova (interni portal, MINTS 17.11., točka 1). Bez
 * sadržaja datoteke: podnesak se rješava na detalju RB-a ({@code /api/rn/{rn}/objections}).
 *
 * <p>Iznajmljivač je nositelj RB-a, kao u registru ({@code RnRepository#RN_FROM}): ime i prezime ili naziv
 * pravne osobe, OIB ili strani porezni broj non-EU iznajmljivača. {@code accommodationName} je naziv objekta.
 * {@code resolved} je suprotno od {@link SuspensionObjectionRepository#UNRESOLVED}, a {@code resolvedAt} je
 * trenutak odluke ili zatvaranja ({@code null} dok ga nema). {@code countyProcedure}: postupak vodi županija
 * (ručni akt na prijelazu, {@link SuspensionObjectionRepository#COUNTY_PROCEDURE}) — takav podnesak urudžbira i
 * o njemu odlučuje samo županijski službenik; ministarstvo ga vidi, ali čeka županiju.
 */
public record AdminObjectionRowDto(
        UUID objectionId,
        String rn,
        ObjectionKind kind,
        String lessorFirstName,
        String lessorLastName,
        String lessorLegalEntityName,
        String lessorOib,
        String lessorTaxNumber,
        String accommodationName,
        String county,
        Instant submittedAt,
        SuspensionObjectionStatus status,
        boolean countyProcedure,
        boolean resolved,
        Instant resolvedAt
) {

    /** Konstruktorski izraz upita: vrsta dolazi iz statusa broja pri predaji. */
    public AdminObjectionRowDto(UUID objectionId, String rn, RnStatus rnStatus, String lessorFirstName,
                                String lessorLastName, String lessorLegalEntityName, String lessorOib,
                                String lessorTaxNumber, String accommodationName, String county,
                                Instant submittedAt, SuspensionObjectionStatus status, boolean countyProcedure,
                                boolean resolved, Instant resolvedAt) {
        this(objectionId, rn, ObjectionKind.of(rnStatus), lessorFirstName, lessorLastName, lessorLegalEntityName,
                lessorOib, lessorTaxNumber, accommodationName, county, submittedAt, status, countyProcedure, resolved,
                resolvedAt);
    }

    /** Ime i prezime ili naziv pravne osobe, kao stupac „Iznajmljivač" u registru. */
    public String lessorName() {
        if (lessorLegalEntityName != null && !lessorLegalEntityName.isBlank()) {
            return lessorLegalEntityName;
        }
        String name = ((lessorFirstName == null ? "" : lessorFirstName) + " "
                + (lessorLastName == null ? "" : lessorLastName)).trim();
        return name.isEmpty() ? null : name;
    }
}
