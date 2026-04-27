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
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(schema = "str", name = "sso")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class SsoEntity {

    @Id
    @Column(name = "id_sso", nullable = false, updatable = false)
    private UUID idSso;

    @Column(name = "id_zahtjeva", updatable = false)
    private UUID idZahtjeva;

    @Column(name = "id_vrste_sso")
    @Setter private Long idVrsteSso;

    @Column(name = "id_core_objekt")
    @Setter private UUID idCoreObjekt;

    @Column(name = "oznaka_sso", length = 64)
    @Setter private String oznakaSso;

    @Column(name = "zupanija", length = 128, nullable = false)
    private String zupanija;

    @Column(name = "grad", length = 128, nullable = false)
    private String grad;

    @Column(name = "naselje", length = 128)
    @Setter private String naselje;

    @Column(name = "ulica", length = 128, nullable = false)
    private String ulica;

    @Column(name = "kucni_broj", length = 16, nullable = false)
    private String kucniBroj;

    @Column(name = "katastarska_opcina", length = 128)
    @Setter private String katastarskaOpcina;

    @Column(name = "broj_katastarske_cestice", length = 64)
    @Setter private String brojKatastarskeCestice;

    @Column(name = "max_kreveta", nullable = false)
    private int maxKreveta;

    @Column(name = "max_gostiju", nullable = false)
    private int maxGostiju;

    @Enumerated(EnumType.STRING)
    @Column(name = "ponuda", length = 16, nullable = false)
    private Ponuda ponuda;

    @Column(name = "boraviste_iznajmljivaca")
    @Setter private Boolean boravisteIznajmljivaca;

    @Column(name = "prebivaliste_iznajmljivaca")
    @Setter private Boolean prebivalisteIznajmljivaca;

    @Column(name = "naziv", length = 255)
    @Setter private String naziv;

    @Column(name = "skupina", length = 128)
    @Setter private String skupina;

    @Column(name = "trazena_kategorija", length = 32)
    @Setter private String trazenaKategorija;

    @Column(name = "kategorija", length = 32)
    @Setter private String kategorija;

    @Column(name = "opis", length = 1024)
    @Setter private String opis;

    @Column(name = "brko", length = 64)
    @Setter private String brko;

    @Column(name = "napomena", length = 1024)
    @Setter private String napomena;

    @Column(name = "broj_pomocnih_kreveta")
    @Setter private Integer brojPomocnihKreveta;

    @Column(name = "zgrada", nullable = false)
    private boolean zgrada;

    @Column(name = "kat", length = 8)
    @Setter private String kat;

    @Column(name = "stanovi", nullable = false)
    private boolean stanovi;

    @Column(name = "legalizirano", nullable = false)
    private boolean legalizirano;

    @Column(name = "suglasnost_suvlasnika")
    @Setter private Boolean suglasnostSuvlasnika;

    @Column(name = "datum_suglasnosti")
    @Setter private LocalDate datumSuglasnosti;

    @Column(name = "datum_povlacenja_suglasnosti")
    @Setter private LocalDate datumPovlacenjaSuglasnosti;

    @Column(name = "domacin")
    private Boolean domacin;

    @Column(name = "datum_domacina")
    private Instant datumDomacina;

    @Column(name = "datum_povlacenja_dom")
    private Instant datumPovlacenjaDom;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static SsoEntity create(UUID idZahtjeva, String zupanija, String grad, String ulica,
                                   String kucniBroj, int maxKreveta, int maxGostiju, Ponuda ponuda,
                                   boolean zgrada, boolean stanovi, boolean legalizirano) {
        SsoEntity s = new SsoEntity();
        s.idSso = UUID.randomUUID();
        s.idZahtjeva = idZahtjeva;
        s.zupanija = zupanija;
        s.grad = grad;
        s.ulica = ulica;
        s.kucniBroj = kucniBroj;
        s.maxKreveta = maxKreveta;
        s.maxGostiju = maxGostiju;
        s.ponuda = ponuda;
        s.zgrada = zgrada;
        s.stanovi = stanovi;
        s.legalizirano = legalizirano;
        Instant now = Instant.now();
        s.createdAt = now;
        s.updatedAt = now;
        return s;
    }

    public void setLocationDetails(String naselje, String kat, String katastarskaOpcina,
                                   String brojKatastarskeCestice, String oznakaSso,
                                   Boolean boravisteIznajmljivaca, Long idVrsteSso, UUID idCoreObjekt) {
        this.naselje = naselje;
        this.kat = kat;
        this.katastarskaOpcina = katastarskaOpcina;
        this.brojKatastarskeCestice = brojKatastarskeCestice;
        this.oznakaSso = oznakaSso;
        this.boravisteIznajmljivaca = boravisteIznajmljivaca;
        this.idVrsteSso = idVrsteSso;
        this.idCoreObjekt = idCoreObjekt;
        this.updatedAt = Instant.now();
    }

    public void setSuglasnost(Boolean suglasnostSuvlasnika, LocalDate datumSuglasnosti,
                              LocalDate datumPovlacenjaSuglasnosti) {
        this.suglasnostSuvlasnika = suglasnostSuvlasnika;
        this.datumSuglasnosti = datumSuglasnosti;
        this.datumPovlacenjaSuglasnosti = datumPovlacenjaSuglasnosti;
        this.updatedAt = Instant.now();
    }

    public void markDomacin(boolean value) {
        this.domacin = value;
        Instant now = Instant.now();
        if (value && this.datumDomacina == null) {
            this.datumDomacina = now;
        }
        if (!value && this.datumDomacina != null && this.datumPovlacenjaDom == null) {
            this.datumPovlacenjaDom = now;
        }
        this.updatedAt = now;
    }

    public void setSpecDetails(String naziv, String skupina, String trazenaKategorija, String kategorija,
                               String opis, String brko, String napomena, Integer brojPomocnihKreveta,
                               Boolean prebivalisteIznajmljivaca) {
        this.naziv = naziv;
        this.skupina = skupina;
        this.trazenaKategorija = trazenaKategorija;
        this.kategorija = kategorija;
        this.opis = opis;
        this.brko = brko;
        this.napomena = napomena;
        this.brojPomocnihKreveta = brojPomocnihKreveta;
        this.prebivalisteIznajmljivaca = prebivalisteIznajmljivaca;
        this.updatedAt = Instant.now();
    }
}
