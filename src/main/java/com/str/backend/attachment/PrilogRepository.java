package com.str.backend.attachment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PrilogRepository extends JpaRepository<PismenoPrilogEntity, Long> {

    /** Prilog bez sadržaja — popisi, provjere i eGOP petlja ne trebaju BLOB. */
    interface PrilogSazetak {
        Long getId();
        VrstaVlasnika getVrstaVlasnika();
        UUID getVlasnikId();
        String getNazivDatoteke();
        String getContentType();
        long getVelicina();
        String getUcitao();
        Integer getEgopJop();
        Integer getEgopRbr();
        int getEgopPokusaji();
        Instant getEgopSljedeciPokusajU();
    }

    String SAZETAK = """
            SELECT p.id AS id, p.vrstaVlasnika AS vrstaVlasnika, p.vlasnikId AS vlasnikId,
                   p.nazivDatoteke AS nazivDatoteke, p.contentType AS contentType, p.velicina AS velicina,
                   p.ucitao AS ucitao, p.egopJop AS egopJop, p.egopRbr AS egopRbr,
                   p.egopPokusaji AS egopPokusaji, p.egopSljedeciPokusajU AS egopSljedeciPokusajU
            FROM PismenoPrilogEntity p
            """;

    @Transactional(readOnly = true)
    @Query(SAZETAK + " WHERE p.id IN :ids ORDER BY p.id")
    List<PrilogSazetak> findSazeci(@Param("ids") Collection<Long> ids);

    /**
     * Prilozi jednog vlasnika, redom učitavanja (id) — isti redoslijed daje i {@code rbrPriloga}.
     *
     * <p>{@code REQUIRES_NEW}: čitaju ga eGOP prilaganje i obavijest e-poštom iz {@code AFTER_COMMIT}
     * listenera, gdje je izvorna transakcija već dovršena (isti obrazac kao {@code EgopFilingStore}).
     */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    @Query(SAZETAK + " WHERE p.vrstaVlasnika = :vrsta AND p.vlasnikId = :vlasnikId ORDER BY p.id")
    List<PrilogSazetak> findSazeciVlasnika(@Param("vrsta") VrstaVlasnika vrsta, @Param("vlasnikId") UUID vlasnikId);

    @Transactional(readOnly = true)
    @Query(SAZETAK + " WHERE p.vrstaVlasnika = :vrsta AND p.vlasnikId IN :vlasnici ORDER BY p.id")
    List<PrilogSazetak> findSazeciVlasnika(@Param("vrsta") VrstaVlasnika vrsta,
                                           @Param("vlasnici") Collection<UUID> vlasnici);

    @Transactional(readOnly = true)
    long countByUcitaoAndVlasnikIdIsNull(String ucitao);

    /** Ukupna veličina nevezanih priloga učitavatelja — staging nije pohrana, pa je ograničen. */
    @Transactional(readOnly = true)
    @Query("SELECT COALESCE(SUM(p.velicina), 0) FROM PismenoPrilogEntity p"
            + " WHERE p.ucitao = :ucitao AND p.vlasnikId IS NULL")
    long sumVelicinaNevezanih(@Param("ucitao") String ucitao);

    @Transactional(readOnly = true)
    long countByVrstaVlasnikaAndVlasnikId(VrstaVlasnika vrsta, UUID vlasnikId);

    /** Ukupna veličina priloga već vezanih uz vlasnika — u transakciji radnje, bez sadržaja. */
    @Transactional(readOnly = true)
    @Query("SELECT COALESCE(SUM(p.velicina), 0) FROM PismenoPrilogEntity p"
            + " WHERE p.vrstaVlasnika = :vrsta AND p.vlasnikId = :vlasnikId")
    long sumVelicinaVlasnika(@Param("vrsta") VrstaVlasnika vrsta, @Param("vlasnikId") UUID vlasnikId);

    /** Sadržaj priloga; {@code REQUIRES_NEW} iz istog razloga kao {@link #findSazeciVlasnika(VrstaVlasnika, UUID)}. */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    @Query("SELECT p.fileContent FROM PismenoPrilogEntity p WHERE p.id = :id")
    byte[] findSadrzaj(@Param("id") Long id);

    /**
     * Uvjetno vezanje: samo nevezani prilozi istog učitavatelja. Vraća broj vezanih — manji od
     * broja id-eva znači da je paralelna radnja neki prilog u međuvremenu vezala ili obrisala.
     *
     * <p>Bez {@code clearAutomatically}: ide u transakciji radnje, a čišćenje konteksta bi odspojilo
     * entitete koje radnja još mijenja (npr. RB). Prilozi se u njoj čitaju samo kao sažeci.
     */
    @Modifying
    @Query("""
            UPDATE PismenoPrilogEntity p
            SET p.vrstaVlasnika = :vrsta, p.vlasnikId = :vlasnikId, p.vezanoU = :sada
            WHERE p.id IN :ids AND p.ucitao = :ucitao AND p.vlasnikId IS NULL
            """)
    int bind(@Param("ids") Collection<Long> ids, @Param("ucitao") String ucitao,
             @Param("vrsta") VrstaVlasnika vrsta, @Param("vlasnikId") UUID vlasnikId, @Param("sada") Instant sada);

    @Modifying
    @Transactional
    @Query("DELETE FROM PismenoPrilogEntity p WHERE p.id = :id AND p.ucitao = :ucitao AND p.vlasnikId IS NULL")
    int deleteStaged(@Param("id") Long id, @Param("ucitao") String ucitao);

    @Modifying
    @Transactional
    @Query("DELETE FROM PismenoPrilogEntity p WHERE p.vlasnikId IS NULL AND p.ucitanoU < :granica")
    int deleteStagedBefore(@Param("granica") Instant granica);

    /**
     * Prilog je priložen u eGOP — upisuje se odmah nakon SOAP poziva. {@code REQUIRES_NEW} jer se
     * zove iz {@code AFTER_COMMIT} listenera ({@code RnLifecycleFilingListener}, {@code RnIssuedListener}):
     * ondje je izvorna transakcija dovršena, a {@code REQUIRED} bi se pridružio njoj i UPDATE bi pao
     * s {@code TransactionRequiredException} — nakon što je eGOP prilog već primio, pa bi ga retry
     * poslao drugi put. Isti obrazac kao {@code EgopFilingStore} i {@code MailRetryStore}.
     */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("""
            UPDATE PismenoPrilogEntity p
            SET p.egopJop = :jop, p.egopRbr = :rbr, p.egopPrilozenoU = :sada,
                p.egopGreska = NULL, p.egopSljedeciPokusajU = NULL
            WHERE p.id = :id
            """)
    int markEgopPrilozeno(@Param("id") Long id, @Param("jop") int jop, @Param("rbr") int rbr,
                          @Param("sada") Instant sada);

    /**
     * Neuspjelo prilaganje u eGOP: jedan pokušaj više, kad smije sljedeći i zadnja greška. Ne dira
     * već priloženi prilog (paralelni tok ga je u međuvremenu priložio). {@code REQUIRES_NEW} kao
     * {@link #markEgopPrilozeno}.
     */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("""
            UPDATE PismenoPrilogEntity p
            SET p.egopPokusaji = p.egopPokusaji + 1, p.egopSljedeciPokusajU = :sljedeci, p.egopGreska = :greska
            WHERE p.id = :id AND p.egopJop IS NULL
            """)
    int markEgopNeuspjeh(@Param("id") Long id, @Param("greska") String greska,
                         @Param("sljedeci") Instant sljedeci);

    /**
     * Urudžbirana pismena ({@code SYNCED}, imaju JOP) s vezanim, a u eGOP još nepriloženim prilozima
     * koji smiju u ponovni pokušaj — ulaz {@code EgopRetryJob}-a. Pripadnost pismenu je ista kao u
     * {@code PrilogEgopService#priloziPismena}: prilozi zahtjeva idu uz ulazno pismeno zahtjeva, a
     * prilozi prijelaza uz pismeno tog prijelaza ({@code act_ref} = {@code log_id}) — službenika uz
     * izlazno, stranke uz ulazno — a prilozi prigovora uz pismeno očitovanja ili prigovora
     * ({@code act_ref} = {@code PRIGOVOR:<objection_id>}).
     *
     * <p>Prilog koji još nije pokušan čeka {@code grace} od vezanja: prvi pokušaj radi tok urudžbe
     * odmah nakon glavnog dokumenta, a cron mu ga ne smije oteti. Neuspjeli pušta njegov backoff.
     */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    @Query("""
            SELECT e.id FROM EgopPismenoEntity e, PismenoPrilogEntity p
            WHERE e.status = com.str.backend.domain.EgopSyncStatus.SYNCED AND e.jop IS NOT NULL
              AND p.vlasnikId IS NOT NULL AND p.egopJop IS NULL AND p.egopPokusaji < :maxPokusaja
              AND ((p.egopSljedeciPokusajU IS NULL AND p.vezanoU < :grace) OR p.egopSljedeciPokusajU <= :sada)
              AND ((p.vrstaVlasnika = com.str.backend.attachment.VrstaVlasnika.SUBMISSION
                      AND e.submissionId = p.vlasnikId AND e.actRef = :registracija
                      AND e.vrstaPismenaNaziv = :zahtjev)
                OR (p.vrstaVlasnika = com.str.backend.attachment.VrstaVlasnika.RN_LOG
                      AND e.actRef = CAST(p.vlasnikId AS String)
                      AND ((p.ucitao LIKE 'INTERNAL:%' AND e.smjer = com.str.backend.egop.EgopPismenoEntity.Smjer.IZLAZNO)
                        OR (p.ucitao NOT LIKE 'INTERNAL:%' AND e.smjer = com.str.backend.egop.EgopPismenoEntity.Smjer.ULAZNO)))
                OR (p.vrstaVlasnika = com.str.backend.attachment.VrstaVlasnika.OBJECTION
                      AND e.actRef = CONCAT(:prigovor, CAST(p.vlasnikId AS String))))
            GROUP BY e.id
            ORDER BY MIN(p.id)
            """)
    List<UUID> findPismenaZaPonovnoPrilaganje(@Param("maxPokusaja") int maxPokusaja, @Param("grace") Instant grace,
                                              @Param("sada") Instant sada, @Param("registracija") String registracija,
                                              @Param("zahtjev") String zahtjev, @Param("prigovor") String prigovor);

    // Pripadnost RB-u za preuzimanje: prilog tuđeg RB-a pod ovim RB-om je 404.

    @Transactional(readOnly = true)
    @Query("SELECT COUNT(r) > 0 FROM RnEntity r WHERE r.rn = :rn AND r.submissionId = :submissionId")
    boolean isZahtjevRb(@Param("submissionId") UUID submissionId, @Param("rn") String rn);

    @Transactional(readOnly = true)
    @Query("SELECT COUNT(l) > 0 FROM RegistrationNumberLogEntity l WHERE l.logId = :logId AND l.rn = :rn")
    boolean isPrijelazRb(@Param("logId") UUID logId, @Param("rn") String rn);

    @Transactional(readOnly = true)
    @Query("SELECT COUNT(o) > 0 FROM SuspensionObjectionEntity o WHERE o.objectionId = :objectionId AND o.rn = :rn")
    boolean isPrigovorRb(@Param("objectionId") UUID objectionId, @Param("rn") String rn);
}
