package com.str.backend.statistics;

import com.str.backend.rn.RnEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/** Read-only statistics queries that span rn + accommodation — kept separate to avoid
 *  making the rn package depend on statistics DTOs. */
interface StatisticsRepository extends Repository<RnEntity, String> {

    interface DetailRowProjection {
        String getRn();
        String getName();
        String getStreet();
        String getStreetNumber();
        String getCityName();
        String getCounty();
        String getCategory();
        String getOfferType();
        String getStatus();
        LocalDate getIssueDate();
        LocalDate getValidFrom();
        LocalDate getValidTo();
        Integer getMaxBeds();
        Integer getMaxGuests();
    }

    @Transactional(readOnly = true)
    @Query(value = """
            SELECT
                r.rn                              AS rn,
                a.name                            AS name,
                a.street                          AS street,
                a.street_number                   AS "streetNumber",
                COALESCE(m.jls_ime, a.city)       AS "cityName",
                a.county                          AS county,
                a.category                        AS category,
                a.offer_type                      AS "offerType",
                r.status                          AS status,
                r.issue_date                      AS "issueDate",
                r.valid_from                      AS "validFrom",
                r.valid_to                        AS "validTo",
                a.max_beds                        AS "maxBeds",
                a.max_guests                      AS "maxGuests"
            FROM str_rn.registration_number r
            JOIN str_rn.accommodation a ON a.accommodation_id = r.accommodation_id
            LEFT JOIN rpj_dgu.gradovi_i_opcine m ON m.id::text = a.city
            WHERE r.status IN (:statuses)
            ORDER BY a.county ASC, a.name ASC
            """, nativeQuery = true)
    List<DetailRowProjection> findDetailRows(@Param("statuses") List<String> statuses);
}
