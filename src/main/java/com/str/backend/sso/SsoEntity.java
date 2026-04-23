package com.str.backend.sso;

import com.str.backend.domain.Ponuda;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(schema = "str", name = "sso")
public class SsoEntity {

    @Id
    @Column(name = "id_sso", nullable = false, updatable = false)
    private UUID idSso;

    @Column(name = "id_zahtjeva", nullable = false, updatable = false)
    private UUID idZahtjeva;

    @Column(name = "id_vrste_sso")
    private Long idVrsteSso;

    @Column(name = "id_core_objekt")
    private UUID idCoreObjekt;

    @Column(name = "oznaka_sso", length = 64)
    private String oznakaSso;

    @Column(name = "zupanija", length = 128, nullable = false)
    private String zupanija;

    @Column(name = "grad", length = 128, nullable = false)
    private String grad;

    @Column(name = "naselje", length = 128)
    private String naselje;

    @Column(name = "ulica", length = 128, nullable = false)
    private String ulica;

    @Column(name = "kucni_broj", length = 16, nullable = false)
    private String kucniBroj;

    @Column(name = "katastarska_opcina", length = 128)
    private String katastarskaOpcina;

    @Column(name = "broj_katastarske_cestice", length = 64)
    private String brojKatastarskeCestice;

    @Column(name = "max_kreveta", nullable = false)
    private int maxKreveta;

    @Column(name = "max_gostiju", nullable = false)
    private int maxGostiju;

    @Enumerated(EnumType.STRING)
    @Column(name = "ponuda", length = 16, nullable = false)
    private Ponuda ponuda;

    @Column(name = "boraviste_iznajmljivaca")
    private Boolean boravisteIznajmljivaca;

    @Column(name = "zgrada", nullable = false)
    private boolean zgrada;

    @Column(name = "kat", length = 8)
    private String kat;

    @Column(name = "stanovi", nullable = false)
    private boolean stanovi;

    @Column(name = "legalizirano", nullable = false)
    private boolean legalizirano;

    @Column(name = "suglasnost_suvlasnika")
    private Boolean suglasnostSuvlasnika;

    @Column(name = "datum_suglasnosti")
    private LocalDate datumSuglasnosti;

    @Column(name = "datum_povlacenja_suglasnosti")
    private LocalDate datumPovlacenjaSuglasnosti;

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

    protected SsoEntity() {
    }

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

    public UUID getIdSso() { return idSso; }
    public UUID getIdZahtjeva() { return idZahtjeva; }
    public Long getIdVrsteSso() { return idVrsteSso; }
    public UUID getIdCoreObjekt() { return idCoreObjekt; }
    public String getOznakaSso() { return oznakaSso; }
    public String getZupanija() { return zupanija; }
    public String getGrad() { return grad; }
    public String getNaselje() { return naselje; }
    public String getUlica() { return ulica; }
    public String getKucniBroj() { return kucniBroj; }
    public String getKatastarskaOpcina() { return katastarskaOpcina; }
    public String getBrojKatastarskeCestice() { return brojKatastarskeCestice; }
    public int getMaxKreveta() { return maxKreveta; }
    public int getMaxGostiju() { return maxGostiju; }
    public Ponuda getPonuda() { return ponuda; }
    public Boolean getBoravisteIznajmljivaca() { return boravisteIznajmljivaca; }
    public boolean isZgrada() { return zgrada; }
    public String getKat() { return kat; }
    public boolean isStanovi() { return stanovi; }
    public boolean isLegalizirano() { return legalizirano; }
    public Boolean getSuglasnostSuvlasnika() { return suglasnostSuvlasnika; }
    public LocalDate getDatumSuglasnosti() { return datumSuglasnosti; }
    public LocalDate getDatumPovlacenjaSuglasnosti() { return datumPovlacenjaSuglasnosti; }
    public Boolean getDomacin() { return domacin; }
    public Instant getDatumDomacina() { return datumDomacina; }
    public Instant getDatumPovlacenjaDom() { return datumPovlacenjaDom; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
