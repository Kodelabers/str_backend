package com.str.backend.aktivnosti;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public interface SsoAktivnostRepository extends JpaRepository<SsoAktivnostEntity, java.util.UUID> {

    @Query("""
            SELECT a FROM SsoAktivnostEntity a
            WHERE (:idPlatforme IS NULL OR a.idPlatforme = :idPlatforme)
              AND (:rb IS NULL OR a.rb = :rb)
              AND (:od IS NULL OR a.razdobljeDo >= :od)
              AND (:doDate IS NULL OR a.razdobljeOd <= :doDate)
            """)
    List<SsoAktivnostEntity> search(@Param("idPlatforme") Long idPlatforme,
                                    @Param("rb") String rb,
                                    @Param("od") LocalDate od,
                                    @Param("doDate") LocalDate doDate);

    @Modifying
    @Query("DELETE FROM SsoAktivnostEntity a WHERE a.purgeAfter <= :now")
    int purgeExpired(@Param("now") Instant now);
}
