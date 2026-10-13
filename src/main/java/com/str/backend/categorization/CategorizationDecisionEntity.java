package com.str.backend.categorization;

import com.str.backend.exception.IllegalStatusTransitionException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Skenirano papirnato rješenje o kategorizaciji koje nije migrirano u eTurizam.
 *
 * <p>Novo rješenje uvijek je vezano uz izdani RB ({@code rn}): korisnik šalje samo datoteku, a
 * metapodatke objekta (naziv, vrsta, adresa, kapacitet) server prepisuje iz smještaja tog RB-a,
 * da ih nadležno tijelo vidi bez otvaranja skena. Zapisi iz starog samostalnog uploada RB nemaju
 * i mogu nositi metapodatke koje je upisao korisnik.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(schema = "str_rn", name = "categorization_decision")
public class CategorizationDecisionEntity {

    @Id
    @Column(name = "decision_id", updatable = false, nullable = false)
    private UUID decisionId;

    @Column(name = "lessor_oib", updatable = false, nullable = false, length = 11)
    private String lessorOib;

    @Column(name = "object_name", updatable = false, length = 255)
    private String objectName;

    @Column(name = "accommodation_type_code", updatable = false, length = 64)
    private String accommodationTypeCode;

    @Column(name = "address_text", updatable = false, length = 500)
    private String addressText;

    @Column(name = "decision_number", updatable = false, length = 64)
    private String decisionNumber;

    @Column(name = "decision_date", updatable = false)
    private LocalDate decisionDate;

    @Column(name = "max_beds", updatable = false)
    private Integer maxBeds;

    @Column(name = "note", updatable = false, length = 1000)
    private String note;

    @Column(name = "file_name", updatable = false, nullable = false, length = 255)
    private String fileName;

    @Column(name = "content_type", updatable = false, nullable = false, length = 100)
    private String contentType;

    @Column(name = "file_size", updatable = false, nullable = false)
    private long fileSize;

    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Column(name = "file_content", updatable = false, nullable = false)
    private byte[] fileContent;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private CategorizationDecisionStatus status;

    /** RB uz koji je rješenje predano; {@code null} samo za stare samostalne uploade. */
    @Column(name = "rn", updatable = false, length = 20)
    private String rn;

    /** Popunjava se kad nadležno tijelo objekt upiše u eTurizam; do tada zapis živi samo kod nas. */
    @Column(name = "facility_id", length = 64)
    private String facilityId;

    @Column(name = "uploaded_at", updatable = false, nullable = false)
    private Instant uploadedAt;

    @Column(name = "verified_by", length = 255)
    private String verifiedBy;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    /**
     * Rješenje iz kojeg je ovo preneseno na novi RB pri ponovnom zahtjevu nakon opoziva (T15,
     * changeset 136); {@code null} za rješenje koje je korisnik predao.
     */
    @Column(name = "source_decision_id", updatable = false)
    private UUID sourceDecisionId;

    public static CategorizationDecisionEntity create(String lessorOib,
                                                      String rn,
                                                      String fileName,
                                                      String contentType,
                                                      byte[] fileContent,
                                                      CategorizationDecisionMetadata metadata) {
        CategorizationDecisionEntity e = new CategorizationDecisionEntity();
        e.decisionId = UUID.randomUUID();
        e.lessorOib = lessorOib;
        e.rn = rn;
        e.fileName = fileName;
        e.contentType = contentType;
        e.fileContent = fileContent;
        e.fileSize = fileContent.length;
        e.status = CategorizationDecisionStatus.SUBMITTED;
        e.uploadedAt = Instant.now();
        e.objectName = metadata.objectName();
        e.accommodationTypeCode = metadata.accommodationTypeCode();
        e.addressText = metadata.addressText();
        e.decisionNumber = metadata.decisionNumber();
        e.decisionDate = metadata.decisionDate();
        e.maxBeds = metadata.maxBeds();
        e.note = metadata.note();
        return e;
    }

    /**
     * Prijenos rješenja s povučenog RB-a na novi RB istog objekta (ponovni zahtjev nakon opoziva).
     * Kopija datoteke i svih metapodataka, isti status; za {@code VERIFIED} ostaju i tko je i kad
     * potvrdio — rješenje je već pregledano i ne traži se ponovo. Kopija ima vlastitu datoteku, pa
     * izvor smije biti obrisan po isteku retencije.
     *
     * <p>{@code uploadedAt} je trenutak prijenosa, ne izvorne predaje — u internom redu kopija ne
     * smije izgledati kao stara predaja; izvor se nalazi preko {@code sourceDecisionId}.
     *
     * <p>Prenosi se samo aktivno rješenje (SUBMITTED ili VERIFIED); odbijeno ne. Je li objekt isti
     * (pa smije li se rješenje uopće prenijeti) odlučuje pozivatelj ({@code ReissueService}).
     */
    public static CategorizationDecisionEntity copyForReissue(CategorizationDecisionEntity source, String newRn,
                                                              Instant carriedAt) {
        if (source.status != CategorizationDecisionStatus.SUBMITTED
                && source.status != CategorizationDecisionStatus.VERIFIED) {
            throw new IllegalArgumentException("Prenosi se samo predano ili potvrđeno rješenje, ne " + source.status);
        }
        CategorizationDecisionEntity e = new CategorizationDecisionEntity();
        e.decisionId = UUID.randomUUID();
        e.lessorOib = source.lessorOib;
        e.rn = newRn;
        e.fileName = source.fileName;
        e.contentType = source.contentType;
        e.fileContent = source.fileContent.clone();
        e.fileSize = source.fileSize;
        e.status = source.status;
        e.uploadedAt = carriedAt;
        e.objectName = source.objectName;
        e.accommodationTypeCode = source.accommodationTypeCode;
        e.addressText = source.addressText;
        e.decisionNumber = source.decisionNumber;
        e.decisionDate = source.decisionDate;
        e.maxBeds = source.maxBeds;
        e.note = source.note;
        e.facilityId = source.facilityId;
        if (source.status == CategorizationDecisionStatus.VERIFIED) {
            e.verifiedBy = source.verifiedBy;
            e.verifiedAt = source.verifiedAt;
        }
        e.sourceDecisionId = source.decisionId;
        return e;
    }

    /**
     * Nadležno tijelo prihvaća rješenje. Dopušteno samo iz {@link CategorizationDecisionStatus#SUBMITTED}.
     *
     * <p>{@code facilityId} se ovdje NE postavlja: upis objekta u eTurizam i dodjela oznake su
     * odvojen korak koji radi eTurizam servis ({@code str.*} nam je read-only). Do potvrde tog
     * ugovora s MINTS-om {@code VERIFIED} je čista oznaka — v. TODO u {@code AdminCategorizationDecisionService}.
     */
    public void verify(String actor) {
        requireSubmitted();
        this.status = CategorizationDecisionStatus.VERIFIED;
        this.verifiedBy = actor;
        this.verifiedAt = Instant.now();
    }

    /** Konačno odbijanje. Dopušteno samo iz {@link CategorizationDecisionStatus#SUBMITTED}. */
    public void reject(String actor) {
        requireSubmitted();
        this.status = CategorizationDecisionStatus.REJECTED;
        this.verifiedBy = actor;
        this.verifiedAt = Instant.now();
    }

    /** Verify i reject su dopušteni samo nad neobrađenim (SUBMITTED) zapisom — inače 409. */
    private void requireSubmitted() {
        if (status != CategorizationDecisionStatus.SUBMITTED) {
            throw new IllegalStatusTransitionException(
                    "Rješenje " + decisionId + " nije u statusu SUBMITTED (trenutno: " + status + ")");
        }
    }

    /** Metapodaci objekta — svi opcionalni, v. komentar na razredu. */
    public record CategorizationDecisionMetadata(String objectName,
                                                 String accommodationTypeCode,
                                                 String addressText,
                                                 String decisionNumber,
                                                 LocalDate decisionDate,
                                                 Integer maxBeds,
                                                 String note) {
    }
}
