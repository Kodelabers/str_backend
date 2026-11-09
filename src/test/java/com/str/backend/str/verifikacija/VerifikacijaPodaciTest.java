package com.str.backend.str.verifikacija;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.accommodation.AccommodationRepository;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.lessor.LessorRepository;
import com.str.backend.lookup.AccommodationTypeEntity;
import com.str.backend.lookup.AccommodationTypeRepository;
import com.str.backend.request.SubmissionEntity;
import com.str.backend.request.SubmissionRepository;
import com.str.backend.rn.RnEntity;
import com.str.backend.rn.RnRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VerifikacijaPodaciTest {

    private static final String RN = "HR180001000000000123";
    private static final UUID SUBMISSION = UUID.randomUUID();
    private static final UUID ACCOMMODATION = UUID.randomUUID();
    private static final UUID LESSOR = UUID.randomUUID();

    private final RnRepository rnRepository = mock(RnRepository.class);
    private final AccommodationRepository accommodationRepository = mock(AccommodationRepository.class);
    private final AccommodationTypeRepository typeRepository = mock(AccommodationTypeRepository.class);
    private final SubmissionRepository submissionRepository = mock(SubmissionRepository.class);
    private final LessorRepository lessorRepository = mock(LessorRepository.class);
    private final VerifikacijaPodaci podaci = new VerifikacijaPodaci(rnRepository, accommodationRepository,
            typeRepository, submissionRepository, lessorRepository);

    private final AccommodationEntity smjestaj = mock(AccommodationEntity.class);
    private final LessorEntity iznajmljivac = mock(LessorEntity.class);

    private final VerifikacijaOutboxEntity zapis = VerifikacijaOutboxEntity.pendingNoviObjekt(RN, SUBMISSION,
            UUID.randomUUID(), "Splitsko-dalmatinska županija", "Apartman Ana", "Brela", Instant.now(), Instant.now());

    @BeforeEach
    void postojeciPodaci() {
        RnEntity rn = mock(RnEntity.class);
        when(rn.getAccommodationId()).thenReturn(ACCOMMODATION);
        when(rnRepository.findById(RN)).thenReturn(Optional.of(rn));
        when(accommodationRepository.findById(ACCOMMODATION)).thenReturn(Optional.of(smjestaj));
        when(smjestaj.getAccommodationTypeId()).thenReturn(7L);
        when(smjestaj.getSettlement()).thenReturn("Brela");
        when(smjestaj.getStreet()).thenReturn("Obala");
        when(smjestaj.getStreetNumber()).thenReturn("5");
        when(smjestaj.getPostalCode()).thenReturn("21322");
        when(smjestaj.getCity()).thenReturn("Brela");
        AccommodationTypeEntity vrsta = mock(AccommodationTypeEntity.class);
        when(vrsta.getName()).thenReturn("Aparthotel");
        when(vrsta.getGroup()).thenReturn("Hoteli");
        when(typeRepository.findById(7L)).thenReturn(Optional.of(vrsta));
        SubmissionEntity predaja = mock(SubmissionEntity.class);
        when(predaja.getLessorId()).thenReturn(LESSOR);
        when(submissionRepository.findById(SUBMISSION)).thenReturn(Optional.of(predaja));
        when(lessorRepository.findById(LESSOR)).thenReturn(Optional.of(iznajmljivac));
        when(iznajmljivac.getLessorOib()).thenReturn("12312312316");
        when(iznajmljivac.getFirstName()).thenReturn("PERO");
        when(iznajmljivac.getLastName()).thenReturn("PERIĆ");
    }

    @Test
    void fizickaOsoba_svaPoljaUObliku() throws Exception {
        JsonNode json = new ObjectMapper().readTree(podaci.sastavi(zapis));

        assertThat(json.get("subjekt").get("oib").asText()).isEqualTo("12312312316");
        assertThat(json.get("subjekt").get("naziv").asText()).isEqualTo("PERO PERIĆ");
        assertThat(json.get("subjekt").get("adresa").isNull()).isTrue();
        assertThat(json.get("objekt").get("skupina").asText()).isEqualTo("Hoteli");
        assertThat(json.get("objekt").get("vrsta").asText()).isEqualTo("Aparthotel");
        assertThat(json.get("objekt").get("naziv").asText()).isEqualTo("Apartman Ana");
        assertThat(json.get("objekt").get("adresa").asText()).isEqualTo("Brela, Obala 5, 21322 Brela");
        assertThat(json.get("rijeseno").isNull()).isTrue();
    }

    @Test
    void pravnaOsoba_nazivTvrtke() throws Exception {
        when(iznajmljivac.getLegalEntityName()).thenReturn("TESTNA FIRMA D.O.O.");

        JsonNode json = new ObjectMapper().readTree(podaci.sastavi(zapis));

        assertThat(json.get("subjekt").get("naziv").asText()).isEqualTo("TESTNA FIRMA D.O.O.");
    }

    /** Bez smještaja i predaje obavijest se i dalje upisuje: naziv iz retka, ostalo {@code null}. */
    @Test
    void bezPovezanihPodataka_ostajeNull() throws Exception {
        when(rnRepository.findById(any())).thenReturn(Optional.empty());
        when(submissionRepository.findById(any())).thenReturn(Optional.empty());

        JsonNode json = new ObjectMapper().readTree(podaci.sastavi(zapis));

        assertThat(json.get("subjekt").isNull()).isTrue();
        assertThat(json.get("objekt").get("naziv").asText()).isEqualTo("Apartman Ana");
        assertThat(json.get("objekt").get("adresa").isNull()).isTrue();
    }
}
