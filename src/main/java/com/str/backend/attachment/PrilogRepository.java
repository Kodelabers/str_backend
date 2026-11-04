package com.str.backend.attachment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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
    }

    String SAZETAK = """
            SELECT p.id AS id, p.vrstaVlasnika AS vrstaVlasnika, p.vlasnikId AS vlasnikId,
                   p.nazivDatoteke AS nazivDatoteke, p.contentType AS contentType, p.velicina AS velicina,
                   p.ucitao AS ucitao, p.egopJop AS egopJop, p.egopRbr AS egopRbr
            FROM PismenoPrilogEntity p
            """;

    @Transactional(readOnly = true)
    @Query(SAZETAK + " WHERE p.id IN :ids ORDER BY p.id")
    List<PrilogSazetak> findSazeci(@Param("ids") Collection<Long> ids);

    /** Prilozi jednog vlasnika, redom učitavanja (id) — isti redoslijed daje i {@code rbrPriloga}. */
    @Transactional(readOnly = true)
    @Query(SAZETAK + " WHERE p.vrstaVlasnika = :vrsta AND p.vlasnikId = :vlasnikId ORDER BY p.id")
    List<PrilogSazetak> findSazeciVlasnika(@Param("vrsta") VrstaVlasnika vrsta, @Param("vlasnikId") UUID vlasnikId);

    @Transactional(readOnly = true)
    @Query(SAZETAK + " WHERE p.vrstaVlasnika = :vrsta AND p.vlasnikId IN :vlasnici ORDER BY p.id")
    List<PrilogSazetak> findSazeciVlasnika(@Param("vrsta") VrstaVlasnika vrsta,
                                           @Param("vlasnici") Collection<UUID> vlasnici);

    @Transactional(readOnly = true)
    long countByUcitaoAndVlasnikIdIsNull(String ucitao);

    @Transactional(readOnly = true)
    long countByVrstaVlasnikaAndVlasnikId(VrstaVlasnika vrsta, UUID vlasnikId);

    @Transactional(readOnly = true)
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

    /** Prilog je priložen u eGOP — upisuje se odmah nakon SOAP poziva, u vlastitoj transakciji. */
    @Modifying
    @Transactional
    @Query("""
            UPDATE PismenoPrilogEntity p
            SET p.egopJop = :jop, p.egopRbr = :rbr, p.egopPrilozenoU = :sada
            WHERE p.id = :id
            """)
    int markEgopPrilozeno(@Param("id") Long id, @Param("jop") int jop, @Param("rbr") int rbr,
                          @Param("sada") Instant sada);

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
