package com.str.backend.lookup;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;

@Entity
@Table(schema = "str", name = "vrsta_zahtjeva")
public class VrstaZahtjevaEntity {

    @Id
    @Column(name = "oznaka_vrste", length = 32, nullable = false, updatable = false)
    private String oznakaVrste;

    @Column(name = "kodna_oznaka", length = 16, nullable = false)
    private String kodnaOznaka;

    @Column(name = "naziv", nullable = false)
    private String naziv;

    @Column(name = "datum_od", nullable = false)
    private LocalDate datumOd;

    @Column(name = "datum_do")
    private LocalDate datumDo;

    @Column(name = "status", length = 16, nullable = false)
    private String status;

    protected VrstaZahtjevaEntity() {
    }

    public VrstaZahtjevaEntity(String oznakaVrste, String kodnaOznaka, String naziv,
                               LocalDate datumOd, LocalDate datumDo, String status) {
        this.oznakaVrste = oznakaVrste;
        this.kodnaOznaka = kodnaOznaka;
        this.naziv = naziv;
        this.datumOd = datumOd;
        this.datumDo = datumDo;
        this.status = status;
    }

    public String getOznakaVrste() { return oznakaVrste; }
    public String getKodnaOznaka() { return kodnaOznaka; }
    public String getNaziv() { return naziv; }
    public LocalDate getDatumOd() { return datumOd; }
    public LocalDate getDatumDo() { return datumDo; }
    public String getStatus() { return status; }
}
