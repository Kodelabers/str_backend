package com.str.backend.iznajmljivac;

import com.str.backend.domain.StatusPrijave;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "str", name = "iznajmljivac")
public class IznajmljivacEntity {

    @Id
    @Column(name = "id_iznajmljivaca", nullable = false, updatable = false)
    private UUID idIznajmljivaca;

    @Column(name = "oib_zastupnika", length = 11)
    private String oibZastupnika;

    @Column(name = "ime", length = 128, nullable = false)
    private String ime;

    @Column(name = "prezime", length = 128, nullable = false)
    private String prezime;

    @Column(name = "naziv_pravne_osobe")
    private String nazivPravneOsobe;

    @Column(name = "ulica", length = 128, nullable = false)
    private String ulica;

    @Column(name = "kucni_broj", length = 16, nullable = false)
    private String kucniBroj;

    @Column(name = "mjesto", length = 128, nullable = false)
    private String mjesto;

    @Column(name = "zupanija", length = 128, nullable = false)
    private String zupanija;

    @Column(name = "ime_kontakta")
    private String imeKontakta;

    @Column(name = "broj_telefona", length = 32)
    private String brojTelefona;

    @Column(name = "broj_mobitela", length = 32)
    private String brojMobitela;

    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "zastupnik_pravne_osobe")
    private String zastupnikPravneOsobe;

    @Column(name = "email_zastupnika")
    private String emailZastupnika;

    @Column(name = "telefon_zastupnika", length = 32)
    private String telefonZastupnika;

    @Column(name = "korisnicko_ime", length = 128)
    private String korisnickoIme;

    @Column(name = "lozinka_hash")
    private String lozinkaHash;

    @Column(name = "id_sluzbene_osobe", length = 64)
    private String idSluzbeneOsobe;

    @Enumerated(EnumType.STRING)
    @Column(name = "status_prijave", length = 32, nullable = false)
    private StatusPrijave statusPrijave;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected IznajmljivacEntity() {
    }

    public static IznajmljivacEntity create(String ime, String prezime, String ulica, String kucniBroj,
                                            String mjesto, String zupanija, String email) {
        IznajmljivacEntity e = new IznajmljivacEntity();
        e.idIznajmljivaca = UUID.randomUUID();
        e.ime = ime;
        e.prezime = prezime;
        e.ulica = ulica;
        e.kucniBroj = kucniBroj;
        e.mjesto = mjesto;
        e.zupanija = zupanija;
        e.email = email;
        e.statusPrijave = StatusPrijave.AKTIVAN;
        Instant now = Instant.now();
        e.createdAt = now;
        e.updatedAt = now;
        return e;
    }

    public void setLegalEntity(String oibZastupnika, String nazivPravneOsobe, String zastupnik,
                               String emailZastupnika, String telefonZastupnika) {
        this.oibZastupnika = oibZastupnika;
        this.nazivPravneOsobe = nazivPravneOsobe;
        this.zastupnikPravneOsobe = zastupnik;
        this.emailZastupnika = emailZastupnika;
        this.telefonZastupnika = telefonZastupnika;
        this.updatedAt = Instant.now();
    }

    public void setContact(String imeKontakta, String brojTelefona, String brojMobitela) {
        this.imeKontakta = imeKontakta;
        this.brojTelefona = brojTelefona;
        this.brojMobitela = brojMobitela;
        this.updatedAt = Instant.now();
    }

    public void setCredentials(String korisnickoIme, String lozinkaHash) {
        this.korisnickoIme = korisnickoIme;
        this.lozinkaHash = lozinkaHash;
        this.updatedAt = Instant.now();
    }

    public void setStatusPrijave(StatusPrijave statusPrijave) {
        this.statusPrijave = statusPrijave;
        this.updatedAt = Instant.now();
    }

    public UUID getIdIznajmljivaca() { return idIznajmljivaca; }
    public String getOibZastupnika() { return oibZastupnika; }
    public String getIme() { return ime; }
    public String getPrezime() { return prezime; }
    public String getNazivPravneOsobe() { return nazivPravneOsobe; }
    public String getUlica() { return ulica; }
    public String getKucniBroj() { return kucniBroj; }
    public String getMjesto() { return mjesto; }
    public String getZupanija() { return zupanija; }
    public String getImeKontakta() { return imeKontakta; }
    public String getBrojTelefona() { return brojTelefona; }
    public String getBrojMobitela() { return brojMobitela; }
    public String getEmail() { return email; }
    public String getZastupnikPravneOsobe() { return zastupnikPravneOsobe; }
    public String getEmailZastupnika() { return emailZastupnika; }
    public String getTelefonZastupnika() { return telefonZastupnika; }
    public String getKorisnickoIme() { return korisnickoIme; }
    public String getLozinkaHash() { return lozinkaHash; }
    public String getIdSluzbeneOsobe() { return idSluzbeneOsobe; }
    public StatusPrijave getStatusPrijave() { return statusPrijave; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
