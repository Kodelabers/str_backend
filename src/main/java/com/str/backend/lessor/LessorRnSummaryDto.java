package com.str.backend.lessor;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.str.backend.categorization.CategorizationDecisionStatus;
import com.str.backend.domain.RnStatus;
import com.str.backend.objection.SuspensionObjectionStatus;

import java.time.LocalDate;

/**
 * Red „Mojih registracijskih brojeva".
 *
 * <p>{@code categorizationRequired} je {@code true} za RB novog objekta (smještaj bez eTurizam
 * {@code facilityId}) koji nije povučen — samo uz takav RB rješenje o kategorizaciji ima smisla,
 * isto pravilo kao {@code CategorizationDecisionService.UPLOAD_ALLOWED_RN_STATUSES}.
 * {@code categorizationStatus} je status mjerodavnog rješenja uz RB ili {@code null} kad ga
 * nema — postoji i uz RB neverificiranog eTurizam objekta kad je rješenje (neobavezno) predano.
 * Oba popisa (NIAS i non-EU) ih računaju jednako; non-EU iznajmljivač rješenje predaje naknadno,
 * s popisa ({@code POST /api/lessor/registrations/{rn}/categorization-decision}).
 *
 * <p>{@code objectionStatus} je status zadnjeg prigovora na prijedlog suspenzije / suspenziju uz
 * RB ili {@code null} kad ga nema ({@code RnRepository.OBJECTION_STATUS}). Dok je prigovor predan
 * ({@code SUBMITTED}), novi se ne može predati. Računaju ga oba popisa.
 *
 * <p>{@code facilityVerified} je stupac „Verificiran": je li objekt uz RB danas verificiran u
 * eTurizmu ({@code str.RnFacilityVerification}); RB novog objekta je {@code false}, a
 * {@code null} kad eTurizam nije dostupan. Računa ga samo NIAS popis — non-EU iznajmljivač nema
 * eTurizam objekata, pa je ondje {@code null}.
 * {@code facilityId} služi samo tom izračunu i ne ide u odgovor.
 *
 * <p>{@code ponovniZahtjevDo} je zadnji dan do kojeg se za povučeni RB novog objekta smije podnijeti
 * ponovni zahtjev s preuzetim podacima (T15, {@code ReissueService}); {@code null} kad to nije
 * moguće — RB nije povučen, objekt je iz eTurizma, rok je istekao ili je RB već iskorišten.
 * Računa se na oba popisa (NIAS i non-EU) nakon upita ({@link #withPonovniZahtjevDo}).
 *
 * <p>{@code noviBrojIzdan} (T12) je, samo za povučen ({@code WITHDRAWN}) RB, {@code true} kad je za isti
 * objekt u međuvremenu izdan drugi važeći broj — prigovor na povlačenje tada se ne može predati
 * ({@code OBJECTION_RN_REISSUED}), pa frontend skriva gumb. Za ostale statuse je {@code null}.
 * Računa se na oba popisa nakon upita ({@link #withNoviBrojIzdan}).
 *
 * <p>{@code prijedlogIzSuspenzije} (T12) je {@code true} samo uz {@code WITHDRAWAL_PROPOSED} pokrenut iz
 * suspenzije — broj tada javno ostaje suspendiran. Puni ga upit, na oba popisa.
 */
public record LessorRnSummaryDto(
        String rn,
        RnStatus status,
        LocalDate issueDate,
        String accommodationName,
        String street,
        String streetNumber,
        String city,
        String accommodationTypeName,
        boolean categorizationRequired,
        CategorizationDecisionStatus categorizationStatus,
        SuspensionObjectionStatus objectionStatus,
        @JsonIgnore String facilityId,
        Boolean facilityVerified,
        LocalDate ponovniZahtjevDo,
        Boolean noviBrojIzdan,
        boolean prijedlogIzSuspenzije
) {

    /** Popisi iz upita — {@code facilityVerified} i ostalo se dopunjava nakon upita ({@link #withFacilityVerified}). */
    public LessorRnSummaryDto(String rn, RnStatus status, LocalDate issueDate,
                              String accommodationName, String street, String streetNumber,
                              String city, String accommodationTypeName,
                              boolean categorizationRequired,
                              CategorizationDecisionStatus categorizationStatus,
                              SuspensionObjectionStatus objectionStatus,
                              String facilityId, boolean prijedlogIzSuspenzije) {
        this(rn, status, issueDate, accommodationName, street, streetNumber, city,
                accommodationTypeName, categorizationRequired, categorizationStatus, objectionStatus, facilityId,
                null, null, null, prijedlogIzSuspenzije);
    }

    /** Kao gornji, bez prijedloga povlačenja iz suspenzije. */
    public LessorRnSummaryDto(String rn, RnStatus status, LocalDate issueDate,
                              String accommodationName, String street, String streetNumber,
                              String city, String accommodationTypeName,
                              boolean categorizationRequired,
                              CategorizationDecisionStatus categorizationStatus,
                              SuspensionObjectionStatus objectionStatus,
                              String facilityId) {
        this(rn, status, issueDate, accommodationName, street, streetNumber, city, accommodationTypeName,
                categorizationRequired, categorizationStatus, objectionStatus, facilityId, false);
    }

    /** Za upite koji stanje rješenja ne računaju (non-EU popis). */
    public LessorRnSummaryDto(String rn, RnStatus status, LocalDate issueDate,
                              String accommodationName, String street, String streetNumber,
                              String city, String accommodationTypeName) {
        this(rn, status, issueDate, accommodationName, street, streetNumber, city,
                accommodationTypeName, false, null, null, null, null, null, null, false);
    }

    public LessorRnSummaryDto withFacilityVerified(boolean verified) {
        return new LessorRnSummaryDto(rn, status, issueDate, accommodationName, street, streetNumber,
                city, accommodationTypeName, categorizationRequired, categorizationStatus, objectionStatus,
                facilityId, verified, ponovniZahtjevDo, noviBrojIzdan, prijedlogIzSuspenzije);
    }

    public LessorRnSummaryDto withPonovniZahtjevDo(LocalDate until) {
        return new LessorRnSummaryDto(rn, status, issueDate, accommodationName, street, streetNumber,
                city, accommodationTypeName, categorizationRequired, categorizationStatus, objectionStatus,
                facilityId, facilityVerified, until, noviBrojIzdan, prijedlogIzSuspenzije);
    }

    public LessorRnSummaryDto withNoviBrojIzdan(Boolean izdan) {
        return new LessorRnSummaryDto(rn, status, issueDate, accommodationName, street, streetNumber,
                city, accommodationTypeName, categorizationRequired, categorizationStatus, objectionStatus,
                facilityId, facilityVerified, ponovniZahtjevDo, izdan, prijedlogIzSuspenzije);
    }
}
