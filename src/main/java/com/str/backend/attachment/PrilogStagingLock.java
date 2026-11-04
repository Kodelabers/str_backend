package com.str.backend.attachment;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/**
 * Serijalizira staging istog učitavatelja. Provjera kvote ({@code max-staged-per-uploader}, ukupna
 * veličina) i upis su „prebroji pa upiši": bez zaključavanja paralelni zahtjevi istog korisnika
 * vide isti broj i svi prođu, pa se kvota zaobilazi navalom uploada.
 *
 * <p>Na PostgreSQL-u je to {@code pg_advisory_xact_lock(hashtext(ucitao))} — drži se do kraja
 * transakcije stagea, a ne dira nijedan redak (učitavatelj nema svoj redak koji bi se zaključao).
 * Sudar hashtexta dvaju učitavatelja samo ih nepotrebno serijalizira. Ostale baze (H2 u testovima)
 * advisory lock nemaju, pa ondje ne radi ništa.
 */
@Component
public class PrilogStagingLock {

    private final JdbcTemplate jdbc;
    private volatile Boolean postgres;

    public PrilogStagingLock(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Unutar transakcije stagea ({@code MANDATORY}): lock se otpušta njezinim krajem. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void zakljucaj(String ucitao) {
        if (isPostgres()) {
            jdbc.queryForObject("SELECT 1 FROM pg_advisory_xact_lock(hashtext(?))", Integer.class, ucitao);
        }
    }

    private boolean isPostgres() {
        Boolean p = postgres;
        if (p == null) {
            String product = jdbc.execute((ConnectionCallback<String>) c -> c.getMetaData().getDatabaseProductName());
            p = product != null && product.toLowerCase(Locale.ROOT).contains("postgres");
            postgres = p;
        }
        return p;
    }
}
