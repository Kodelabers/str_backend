package com.str.backend.str;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

@Transactional(readOnly = true)
public interface StrFacilityRepository extends JpaRepository<StrFacilityEntity, Long> {

    long countByActiveTrue();

    /**
     * Upisuje dodijeljeni RB natrag u eTurizam registar, po dogovoru s tuStartom.
     *
     * <p>Ovo je <strong>jedini</strong> put pisanja u shemu {@code str}, koja je inače
     * read-only za ovaj servis. Namjerno je izveden kao uski native UPDATE nad jednom
     * kolonom umjesto kroz entitet: {@link StrFacilityEntity} ostaje {@code @Immutable},
     * pa nijedan drugi tok ne može slučajno perzistirati promjenu u tuđu tablicu.
     *
     * <p>{@code WHERE registration_number IS NULL} sprječava prepisivanje RB-a koji je
     * objekt već dobio (ponovni pokušaj, ručni upis u eTurizmu) — write-back je time
     * idempotentan i ne može tiho pregaziti tuđi podatak.
     *
     * @return broj ažuriranih redaka: 1 kad je upis prošao, 0 kad objekt ne postoji ili
     * već ima RB
     */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE str.facility
               SET registration_number = :rn
             WHERE id = :facilityId
               AND registration_number IS NULL
            """, nativeQuery = true)
    int writeBackRegistrationNumber(@Param("facilityId") long facilityId, @Param("rn") String rn);
}
