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
    }

    @Query(value = """
            SELECT dc.name AS name, dc.phone AS phone, dc.mobile AS mobile
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
}