package com.str.backend.str;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Transactional(readOnly = true)
public interface StrSubjectRepository extends JpaRepository<StrSubjectEntity, Long> {

    Optional<StrSubjectEntity> findFirstByJipsAndActiveTrue(String jips);

    interface DocumentContactRow {
        String getName();
        String getPhone();
        String getMobile();
        String getEmail();
    }

    /**
     * Kontakt s najnovijeg dokumenta subjekta koji ga ima. Kontakt nose samo neke vrste dokumenata
     * (zahtjevi), a svaki dokument ima najviše jedan aktivni kontakt (CDU, 08.10.2026.: 3289 od
     * 3289). Za tvrtku se traži po OIB-u tvrtke.
     */
    @Query(value = """
            SELECT dc.name AS name, dc.phone AS phone, dc.mobile AS mobile, dc.email AS email
            FROM str.subject s
            JOIN str.subject_version sv ON sv.subject_id = s.id
              AND sv.active = true AND sv.historical = false
            JOIN str.document d ON d.subject_version_id = sv.id AND d.active = true
            JOIN str.document_contact dc ON dc.document_id = d.id AND dc.active = true
            WHERE s.jips = :oib
            ORDER BY sv.id DESC, d.id DESC
            LIMIT 1
            """, nativeQuery = true)
    Optional<DocumentContactRow> findDocumentContactByOib(@Param("oib") String oib);

    interface RepresentativeRow {
        String getOib();
        String getFirstName();
        String getLastName();
    }

    /**
     * Jedan zakonski zastupnik tvrtke: {@code document.subject_representative_id} pokazuje na
     * {@code subject_version} zastupnika (CDU, 08.10.2026.: 6926 od 6926 dokumenata). Prednost ima
     * {@code :preferredOib} (NIAS osoba koja podnosi zahtjev), inače zastupnik s najnovijeg
     * dokumenta — redoslijed je potpun, pa je rezultat uvijek isti.
     */
    @Query(value = """
            SELECT rv.pin AS oib, rv.first_name AS firstName, rv.last_name AS lastName
            FROM str.subject s
            JOIN str.subject_version sv ON sv.subject_id = s.id
              AND sv.active = true AND sv.historical = false
            JOIN str.document d ON d.subject_version_id = sv.id AND d.active = true
            JOIN str.subject_version rv ON rv.id = d.subject_representative_id AND rv.active = true
            WHERE s.jips = :legalOib AND s.active = true AND rv.pin IS NOT NULL
            ORDER BY CASE WHEN rv.pin = :preferredOib THEN 0 ELSE 1 END, sv.id DESC, d.id DESC
            LIMIT 1
            """, nativeQuery = true)
    Optional<RepresentativeRow> findRepresentativeByLegalOib(@Param("legalOib") String legalOib,
                                                             @Param("preferredOib") String preferredOib);
}
