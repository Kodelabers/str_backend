package com.str.backend.attachment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Prilog uz pismeno (changeset 148). Nastaje <b>nevezan</b> ({@link #vlasnikId} {@code null}) kad ga
 * korisnik učita, a radnja ga u svojoj transakciji veže uz zahtjev, prijelaz RB-a ili prigovor
 * ({@code PrilogRepository#bind}). Nevezan prilog briše {@code PrilogCleanupJob}.
 *
 * <p>{@link #egopJop} je oznaka da je prilog priložen u eGOP: eGOP nema idempotentno prilaganje,
 * pa retry preskače priloge koji je već imaju (kao {@code egop_pismeno.document_attached}).
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(schema = "str_rn", name = "pismeno_prilog")
public class PismenoPrilogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", updatable = false, nullable = false)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "vrsta_vlasnika", length = 16)
    private VrstaVlasnika vrstaVlasnika;

    @Column(name = "vlasnik_id")
    private UUID vlasnikId;

    @Column(name = "naziv_datoteke", updatable = false, nullable = false, length = 255)
    private String nazivDatoteke;

    @Column(name = "content_type", updatable = false, nullable = false, length = 100)
    private String contentType;

    @Column(name = "velicina", updatable = false, nullable = false)
    private long velicina;

    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Column(name = "file_content", updatable = false, nullable = false)
    private byte[] fileContent;

    @Column(name = "sha256", updatable = false, nullable = false, length = 64)
    private String sha256;

    /** {@link Ucitao}: {@code NIAS:<oib vlasnika>}, {@code LESSOR:<id>} ili {@code INTERNAL:<oib>}. */
    @Column(name = "ucitao", updatable = false, nullable = false, length = 128)
    private String ucitao;

    @Column(name = "ucitano_u", updatable = false, nullable = false)
    private Instant ucitanoU;

    @Column(name = "vezano_u")
    private Instant vezanoU;

    @Column(name = "egop_jop")
    private Integer egopJop;

    @Column(name = "egop_rbr")
    private Integer egopRbr;

    @Column(name = "egop_prilozeno_u")
    private Instant egopPrilozenoU;

    /** Nevezan prilog — vlasnika dobiva tek radnjom uz koju je učitan. */
    public static PismenoPrilogEntity staged(String ucitao, String nazivDatoteke, String contentType,
                                             byte[] sadrzaj, String sha256) {
        PismenoPrilogEntity e = new PismenoPrilogEntity();
        e.ucitao = Objects.requireNonNull(ucitao, "ucitao");
        e.nazivDatoteke = Objects.requireNonNull(nazivDatoteke, "nazivDatoteke");
        e.contentType = Objects.requireNonNull(contentType, "contentType");
        e.fileContent = Objects.requireNonNull(sadrzaj, "sadrzaj");
        e.velicina = sadrzaj.length;
        e.sha256 = Objects.requireNonNull(sha256, "sha256");
        e.ucitanoU = Instant.now();
        return e;
    }

    public boolean isVezan() {
        return vlasnikId != null;
    }
}
