package com.str.backend.sso;

import com.str.backend.domain.Ponuda;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(schema = "str", name = "smjestajni_sadrzaj")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class SmjestajniSadrzajEntity {

    @Id
    @Column(name = "id_smjestajnog_sadrzaja", nullable = false, updatable = false)
    private UUID idSmjestajnogSadrzaja;

    @Column(name = "id_sso", nullable = false, updatable = false)
    private UUID idSso;

    @Column(name = "vrsta_sadrzaja", length = 64, nullable = false)
    private String vrstaSadrzaja;

    @Column(name = "oznaka", length = 64)
    private String oznaka;

    @Column(name = "broj_kreveta", nullable = false)
    private int brojKreveta;

    @Column(name = "broj_jednakih", nullable = false)
    private int brojJednakih;

    @Column(name = "kat", length = 8)
    private String kat;

    @Column(name = "kategorija", length = 32)
    private String kategorija;

    @Enumerated(EnumType.STRING)
    @Column(name = "ponuda", length = 16)
    private Ponuda ponuda;

    @Column(name = "boraviste_iznajmljivaca")
    private Boolean boravisteIznajmljivaca;

    @Column(name = "suglasnost_suvlasnika")
    private Boolean suglasnostSuvlasnika;

    @Column(name = "datum_suglasnosti")
    private LocalDate datumSuglasnosti;

    @Column(name = "datum_povlacenja_suglasnosti")
    private LocalDate datumPovlacenjaSuglasnosti;

    @Column(name = "rb", length = 18)
    private String rb;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static SmjestajniSadrzajEntity create(UUID idSso, String vrstaSadrzaja, int brojKreveta,
                                                 int brojJednakih) {
        SmjestajniSadrzajEntity e = new SmjestajniSadrzajEntity();
        e.idSmjestajnogSadrzaja = UUID.randomUUID();
        e.idSso = idSso;
        e.vrstaSadrzaja = vrstaSadrzaja;
        e.brojKreveta = brojKreveta;
        e.brojJednakih = brojJednakih;
        Instant now = Instant.now();
        e.createdAt = now;
        e.updatedAt = now;
        return e;
    }

    public void setDetails(String oznaka, String kat, String kategorija, Ponuda ponuda,
                           Boolean boravisteIznajmljivaca) {
        this.oznaka = oznaka;
        this.kat = kat;
        this.kategorija = kategorija;
        this.ponuda = ponuda;
        this.boravisteIznajmljivaca = boravisteIznajmljivaca;
        this.updatedAt = Instant.now();
    }

    public void setSuglasnost(Boolean suglasnostSuvlasnika, LocalDate datumSuglasnosti,
                              LocalDate datumPovlacenjaSuglasnosti) {
        this.suglasnostSuvlasnika = suglasnostSuvlasnika;
        this.datumSuglasnosti = datumSuglasnosti;
        this.datumPovlacenjaSuglasnosti = datumPovlacenjaSuglasnosti;
        this.updatedAt = Instant.now();
    }

    public void assignRb(String rb) {
        this.rb = rb;
        this.updatedAt = Instant.now();
    }
}
