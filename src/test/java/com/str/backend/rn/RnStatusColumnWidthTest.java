package com.str.backend.rn;

import com.str.backend.domain.RnStatus;
import com.str.backend.domain.RnTrigger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Regresija za changeset 059: {@code SUSPENSION_PROPOSED} (19 znakova) mora stati u kolone
 * statusa. Prije proširenja na VARCHAR(32) suspenzija je pucala na urudžbi u revizijski log
 * (22001: value too long for type character varying(16)). Test perzistira svaki {@link RnStatus}
 * u {@code registration_number.status} i {@code registration_number_log.from/to_status} pa bi
 * svako buduće suženje kolone (ili predugi novi status) palo ovdje.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional // flush validira širinu kolone, a rollback čisti dijeljeni H2 (DB_CLOSE_DELAY=-1)
class RnStatusColumnWidthTest {

    @Autowired private RnRepository rnRepository;
    @Autowired private RegistrationNumberLogRepository logRepository;

    @Test
    void everyStatusFitsInRnAndLogColumns() {
        assertThatCode(() -> {
            int i = 0;
            for (RnStatus status : RnStatus.values()) {
                String rn = "HR" + String.format("%018d", i++);
                RnEntity entity = RnEntity.issue(rn, UUID.randomUUID(), UUID.randomUUID(), LocalDate.now());
                entity.applyStatus(status); // package-private — postavlja najduži status na kolonu
                rnRepository.saveAndFlush(entity);
                logRepository.saveAndFlush(RegistrationNumberLogEntity.transition(
                        rn, RnStatus.ACTIVE.name(), status.name(), RnTrigger.INSPECTION.name(),
                        "referent", "regresijski test"));
            }
        }).doesNotThrowAnyException();
    }
}
