package com.str.backend.iznajmljivac;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "str", name = "iznajmljivac")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class IznajmljivacEntity {

    @Id
    @Column(name = "id_iznajmljivaca", nullable = false, updatable = false)
    private UUID idIznajmljivaca;

    @Column(name = "oib_zastupnika", length = 11)
    @Setter private String oibZastupnika;

    @Column(name = "ime", length = 128, nullable = false, updatable = false)
    private String ime;

    @Column(name = "prezime", length = 128, nullable = false, updatable = false)
    private String prezime;

    @Column(name = "naziv_pravne_osobe")
    @Setter private String nazivPravneOsobe;

    @Column(name = "ulica", length = 128, nullable = false, updatable = false)
    private String ulica;

    @Column(name = "kucni_broj", length = 16, nullable = false, updatable = false)
    private String kucniBroj;

    @Column(name = "mjesto", length = 128, nullable = false, updatable = false)
    private String mjesto;

    @Column(name = "zupanija", length = 128, nullable = false, updatable = false)
    private String zupanija;

    @Column(name = "ime_kontakta")
    @Setter private String imeKontakta;

    @Column(name = "broj_telefona", length = 32)
    @Setter private String brojTelefona;

    @Column(name = "broj_mobitela", length = 32)
    @Setter private String brojMobitela;

    @Column(name = "email", nullable = false, updatable = false)
    private String email;

    @Column(name = "zastupnik_pravne_osobe")
    @Setter private String zastupnikPravneOsobe;

    @Column(name = "email_zastupnika")
    @Setter private String emailZastupnika;

    @Column(name = "telefon_zastupnika", length = 32)
    @Setter private String telefonZastupnika;

    @Column(name = "adresa_zastupnika", length = 255)
    private String adresaZastupnika;

    @Column(name = "napomena_kontakta", length = 1024)
    @Setter private String napomenaKontakta;

    @Column(name = "id_sluzbene_osobe", length = 64)
    @Setter private String idSluzbeneOsobe;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

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

    public void setContact(String imeKontakta, String brojTelefona, String brojMobitela,
                           String napomenaKontakta) {
        this.imeKontakta = imeKontakta;
        this.brojTelefona = brojTelefona;
        this.brojMobitela = brojMobitela;
        this.napomenaKontakta = napomenaKontakta;
        this.updatedAt = Instant.now();
    }

    public void setAdresaZastupnika(String adresaZastupnika) {
        this.adresaZastupnika = adresaZastupnika;
        this.updatedAt = Instant.now();
    }
}
