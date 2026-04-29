package com.str.backend.zahtjev;

import com.str.backend.domain.ZahtjevStatus;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "str", name = "zahtjev")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class ZahtjevEntity {

    @Id
    @Column(name = "id_zahtjeva", nullable = false, updatable = false)
    private UUID idZahtjeva;

    @Column(name = "ur_zahtjeva", length = 64, nullable = false, updatable = false)
    private String urZahtjeva;

    @Column(name = "link_dokumenta", length = 500)
    @Setter private String linkDokumenta;

    @Column(name = "oznaka_vrste", length = 32, nullable = false, updatable = false)
    private String oznakaVrste;

    @Column(name = "id_iznajmljivaca", nullable = false, updatable = false)
    private UUID idIznajmljivaca;

    @Column(name = "id_nadleznog_tijela")
    private Long idNadleznogTijela;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32, nullable = false)
    private ZahtjevStatus status;

    @Column(name = "datum_urudzbe")
    private Instant datumUrudzbe;

    @JdbcTypeCode(SqlTypes.LONGVARBINARY)
    @Column(name = "pdf_sadrzaj")
    @Setter private byte[] pdfSadrzaj;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static ZahtjevEntity create(String urZahtjeva, String oznakaVrste, UUID idIznajmljivaca,
                                       Long idNadleznogTijela, Instant datumUrudzbe,
                                       String linkDokumenta, byte[] pdfSadrzaj) {
        ZahtjevEntity z = new ZahtjevEntity();
        z.idZahtjeva = UUID.randomUUID();
        z.urZahtjeva = urZahtjeva;
        z.oznakaVrste = oznakaVrste;
        z.idIznajmljivaca = idIznajmljivaca;
        z.idNadleznogTijela = idNadleznogTijela;
        z.datumUrudzbe = datumUrudzbe;
        z.linkDokumenta = linkDokumenta;
        z.pdfSadrzaj = pdfSadrzaj;
        z.status = ZahtjevStatus.U_OBRADI;
        Instant now = Instant.now();
        z.createdAt = now;
        z.updatedAt = now;
        return z;
    }

    public void applyStatus(ZahtjevStatus next) {
        this.status = next;
        this.updatedAt = Instant.now();
    }
}
