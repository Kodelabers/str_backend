package com.str.backend.rn;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RegistrationNumberLogRepository extends JpaRepository<RegistrationNumberLogEntity, UUID> {

    List<RegistrationNumberLogEntity> findByRnOrderByOccurredAtAsc(String rn);

    /**
     * Zadnji prijelaz statusa — odatle akti uzimaju razlog. Revizijski trag je pouzdaniji izvor
     * od {@code ?reason=} parametra na zahtjevu, koji nitko ne provjerava.
     */
    Optional<RegistrationNumberLogEntity> findFirstByRnOrderByOccurredAtDesc(String rn);

    /**
     * Zadnji ulazak u zadani status. Suspenzija je dvofazna, pa materijalni razlog (istek
     * suglasnosti, nalaz nadzora…) stoji na prijelazu u {@code SUSPENSION_PROPOSED}, a ne na
     * zadnjem prijelazu — taj nosi samo procesni okidač (istek roka, obustava).
     *
     * <p>Zapis bez promjene statusa (neuvaženi prigovor na mjeru, UAT 25.11.) nije ulazak: akt mjere, njezin
     * ručni akt i prigovor na nju ostaju vezani uz prijelaz kojim je mjera izrečena.
     */
    default Optional<RegistrationNumberLogEntity> findFirstByRnAndToStatusOrderByOccurredAtDesc(
            String rn, String toStatus) {
        return findUlasciUStatus(rn, toStatus, PageRequest.of(0, 1)).stream().findFirst();
    }

    /** Zadnji prijelaz koji je promijenio status (bez zapisa bez promjene statusa, UAT 25.11.). */
    default Optional<RegistrationNumberLogEntity> findZadnjiPrijelaz(String rn) {
        return findPrijelazi(rn, PageRequest.of(0, 1)).stream().findFirst();
    }

    @Query("SELECT l FROM RegistrationNumberLogEntity l WHERE l.rn = :rn"
            + " AND (l.fromStatus IS NULL OR l.fromStatus <> l.toStatus) ORDER BY l.occurredAt DESC")
    List<RegistrationNumberLogEntity> findPrijelazi(@Param("rn") String rn, Pageable pageable);

    /** Prijelazi u status iz drugog statusa, najnoviji prvi — v. {@link #findFirstByRnAndToStatusOrderByOccurredAtDesc}. */
    @Query("SELECT l FROM RegistrationNumberLogEntity l WHERE l.rn = :rn AND l.toStatus = :toStatus"
            + " AND (l.fromStatus IS NULL OR l.fromStatus <> l.toStatus) ORDER BY l.occurredAt DESC")
    List<RegistrationNumberLogEntity> findUlasciUStatus(@Param("rn") String rn, @Param("toStatus") String toStatus,
                                                         Pageable pageable);

    /** Zadnji prijelaz zadanim okidačem — za akte čiji ciljni status ima više ulaza (ACTIVE). */
    Optional<RegistrationNumberLogEntity> findFirstByRnAndTriggerNameOrderByOccurredAtDesc(String rn, String triggerName);

    /**
     * T16: ručni akt prijelaza (KLASA i URBROJ županijskog službenika); prazno kad akt ide kroz eGOP ili
     * zapisa nema. <b>Jedino pravilo</b> po kojem se prepoznaje postupak koji vodi županija: podnesak stranke
     * na taj akt (očitovanje, prigovor) ne ide u eGOP ({@code PodnesakFilingListener}), nego ga službenik
     * urudžbira ručno ({@code SuspensionObjectionAdminService#register}).
     */
    default Optional<RucniAkt> findRucniAkt(UUID logId) {
        return logId == null ? Optional.empty() : findById(logId).flatMap(RegistrationNumberLogEntity::rucniAkt);
    }
}
