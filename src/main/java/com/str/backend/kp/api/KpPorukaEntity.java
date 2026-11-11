package com.str.backend.kp.api;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Jedna poruka u korisnički pretinac preko KP API-ja ({@code str_rn.kp_poruka}, changeset 157).
 *
 * <p>Pismeno ide u jednu ili više poruka ({@code dio} 1, 2, …) kad akt i prilozi zajedno prelaze
 * ograničenje KP-a. {@code idPoruke} je naš UUID i isti je u svakom ponovljenom pokušaju, pa KP istu
 * poruku ne zaprima dvaput.
 *
 * <p>{@code nextAttemptAt} je i zauzeće i odgoda: dok traje slanje, postavljen je na kraj mogućeg poziva
 * (drugi zahtjev poruku tada ne uzima), a nakon neuspjeha na sljedeći dopušteni pokušaj.
 */
@Entity
@Table(schema = "str_rn", name = "kp_poruka")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class KpPorukaEntity {

    static final int MAX_GRESKA = 500;
    static final int MAX_NASLOV = 1000;
    static final int MAX_SADRZAJ = 4000;

    public enum Status {
        /** Čeka slanje (prvi pokušaj ili ponovni). */
        PENDING,
        /** KP je zaprimio poruku. */
        POSLANO,
        /** Primatelj nema aktivan pretinac — konačno; akt je dostavljen e-poštom. */
        NEMA_PRETINCA,
        /** KP je odbio poruku — konačno; razlog u {@code greska}. */
        ODBIJENO,
        /** KP API je odbio naš zahtjev (400 i sl.) — konačno, greška na našoj strani. */
        NEISPRAVNO,
        /** Pokušaji su iscrpljeni. */
        ABANDONED
    }

    @Id
    @Column(name = "id_poruke", nullable = false, updatable = false)
    private UUID idPoruke;

    @Column(name = "egop_pismeno_id", nullable = false, updatable = false)
    private UUID pismenoId;

    @Column(name = "dio", nullable = false, updatable = false)
    private int dio;

    @Column(name = "rn", length = 20, nullable = false, updatable = false)
    private String rn;

    /** OIB primatelja (fizička osoba). */
    @Column(name = "oib", length = 11, nullable = false, updatable = false)
    private String oib;

    @Column(name = "tip_poruke", nullable = false, updatable = false)
    private int tipPoruke;

    /** {@code zu_rb} županije koja je izdala akt — njeni službenici šalju zaostale poruke. */
    @Column(name = "izdavatelj_zu_rb", nullable = false, updatable = false)
    private int izdavateljZuRb;

    @Column(name = "naslov", length = MAX_NASLOV, nullable = false, updatable = false)
    private String naslov;

    @Column(name = "sadrzaj", length = MAX_SADRZAJ, nullable = false, updatable = false)
    private String sadrzaj;

    @Column(name = "klasa", length = 64, updatable = false)
    private String klasa;

    @Column(name = "ur_broj", length = 64, updatable = false)
    private String urBroj;

    /** Poruka nosi PDF akta ({@code egop_pismeno.pdf_content}). */
    @Column(name = "akt", nullable = false, updatable = false)
    private boolean akt;

    @Column(name = "naziv_akta", length = 255, updatable = false)
    private String nazivAkta;

    /** ID-evi priloga ({@code pismeno_prilog.id}) u ovoj poruci, odvojeni zarezom. */
    @Column(name = "prilozi", length = 2000, updatable = false)
    private String prilozi;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private Status status;

    @Column(name = "greska", length = MAX_GRESKA)
    private String greska;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "poslano_at")
    private Instant poslanoAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    static KpPorukaEntity pending(UUID pismenoId, int dio, String rn, String oib, int tipPoruke, int izdavateljZuRb,
                                  String naslov, String sadrzaj, String klasa, String urBroj, boolean akt,
                                  String nazivAkta, List<Long> prilozi, Instant now) {
        KpPorukaEntity e = new KpPorukaEntity();
        e.idPoruke = UUID.randomUUID();
        e.pismenoId = pismenoId;
        e.dio = dio;
        e.rn = rn;
        e.oib = oib;
        e.tipPoruke = tipPoruke;
        e.izdavateljZuRb = izdavateljZuRb;
        e.naslov = skrati(naslov, MAX_NASLOV);
        e.sadrzaj = skrati(sadrzaj, MAX_SADRZAJ);
        e.klasa = klasa;
        e.urBroj = urBroj;
        e.akt = akt;
        e.nazivAkta = nazivAkta;
        e.prilozi = prilozi.isEmpty() ? null
                : prilozi.stream().map(String::valueOf).collect(Collectors.joining(","));
        e.status = Status.PENDING;
        e.nextAttemptAt = mikro(now);
        e.createdAt = now;
        e.updatedAt = now;
        return e;
    }

    public List<Long> priloziIds() {
        return prilozi == null || prilozi.isBlank() ? List.of()
                : Arrays.stream(prilozi.split(",")).map(String::trim).map(Long::valueOf).toList();
    }

    public boolean isPending() {
        return status == Status.PENDING;
    }

    /** Smije li se poslati sada: čeka i nije zauzeta niti odgođena. */
    public boolean slobodna(Instant now) {
        return isPending() && (nextAttemptAt == null || !nextAttemptAt.isAfter(now));
    }

    void zauzmi(Instant now, Instant do_) {
        this.nextAttemptAt = mikro(do_);
        this.updatedAt = now;
    }

    void poslano(Instant now) {
        this.status = Status.POSLANO;
        this.greska = null;
        this.nextAttemptAt = null;
        this.poslanoAt = now;
        this.updatedAt = now;
    }

    void konacno(Status ishod, String greska, Instant now) {
        this.status = ishod;
        this.greska = skrati(greska, MAX_GRESKA);
        this.nextAttemptAt = null;
        this.updatedAt = now;
    }

    /** Neuspjeh koji se ponavlja; vraća broj neuspjelih pokušaja. */
    int neuspjeh(String greska, Instant sljedeci, Instant now) {
        this.attempts++;
        this.greska = skrati(greska, MAX_GRESKA);
        this.nextAttemptAt = mikro(sljedeci);
        this.updatedAt = now;
        return attempts;
    }

    /** Sesija nije prihvaćena: pokušaj se ne broji, poruka odmah čeka drugu sesiju. */
    void oslobodi(String greska, Instant now) {
        this.greska = skrati(greska, MAX_GRESKA);
        this.nextAttemptAt = mikro(now);
        this.updatedAt = now;
    }

    /**
     * Baza vrijeme drži na mikrosekunde i zaokružuje: {@code next_attempt_at = sada} s nanosekundama mogao bi se
     * spremiti malo iza trenutka u kojem ga isti zahtjev odmah traži ({@code <= :now}), pa poruka ne bi bila slobodna.
     */
    private static Instant mikro(Instant t) {
        return t == null ? null : t.truncatedTo(ChronoUnit.MICROS);
    }

    private static String skrati(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
