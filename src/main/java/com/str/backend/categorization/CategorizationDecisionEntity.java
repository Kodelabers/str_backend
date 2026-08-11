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
 * <p>Metapodaci objekta su svi opcionalni: frontend danas šalje samo datoteku, a što korisnik
 * uz nju unosi još nije dogovoreno s MINTS-om. Datoteka, OIB i status su obavezni jer bez njih
 * zapis ne znači ništa.
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

    /** Popunjava se kad nadležno tijelo objekt upiše u eTurizam; do tada zapis živi samo kod nas. */
    @Column(name = "facility_id", length = 64)
    private String facilityId;

    @Column(name = "uploaded_at", updatable = false, nullable = false)
    private Instant uploadedAt;

    @Column(name = "verified_by", length = 255)
    private String verifiedBy;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    public static CategorizationDecisionEntity create(String lessorOib,
                                                      String fileName,
                                                      String contentType,
                                                      byte[] fileContent,
                                                      CategorizationDecisionMetadata metadata) {
        CategorizationDecisionEntity e = new CategorizationDecisionEntity();
        e.decisionId = UUID.randomUUID();
        e.lessorOib = lessorOib;
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

    /** Metapodaci s rješenja — svi opcionalni, v. komentar na razredu. */
    public record CategorizationDecisionMetadata(String objectName,
                                                 String accommodationTypeCode,
                                                 String addressText,
                                                 String decisionNumber,
                                                 LocalDate decisionDate,
                                                 Integer maxBeds,
                                                 String note) {
    }
}
