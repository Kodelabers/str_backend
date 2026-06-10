package com.str.backend.prefill;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "str_rn", name = "registration_prefill")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RegistrationPrefillEntity {

    @Id
    @Column(name = "prefill_id", nullable = false, updatable = false)
    private UUID prefillId;

    @Column(name = "oib", nullable = false, updatable = false, length = 11)
    private String oib;

    @Column(name = "ime", nullable = false, updatable = false, length = 128)
    private String ime;

    @Column(name = "prezime", nullable = false, updatable = false, length = 128)
    private String prezime;

    @Column(name = "kucni_broj_sifra", updatable = false)
    private Long kucniBrojSifra;

    @Column(name = "broj_kreveta", updatable = false)
    private Integer brojKreveta;

    @Column(name = "broj_gostiju", updatable = false)
    private Integer brojGostiju;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static RegistrationPrefillEntity create(String oib,
                                                   String ime,
                                                   String prezime,
                                                   Long kucniBrojSifra,
                                                   Integer brojKreveta,
                                                   Integer brojGostiju) {
        RegistrationPrefillEntity e = new RegistrationPrefillEntity();
        e.prefillId = UUID.randomUUID();
        e.oib = oib;
        e.ime = ime;
        e.prezime = prezime;
        e.kucniBrojSifra = kucniBrojSifra;
        e.brojKreveta = brojKreveta;
        e.brojGostiju = brojGostiju;
        return e;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
