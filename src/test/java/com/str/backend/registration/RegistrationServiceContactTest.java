package com.str.backend.registration;

import com.str.backend.accommodation.AccommodationRepository;
import com.str.backend.address.CadastreResolver;
import com.str.backend.address.HouseNumberRepository;
import com.str.backend.address.CountyEntity;
import com.str.backend.address.CountyRepository;
import com.str.backend.address.MunicipalityRepository;
import com.str.backend.address.SettlementRepository;
import com.str.backend.domain.OfferType;
import com.str.backend.domain.Offering;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.lessor.LessorRepository;
import com.str.backend.lookup.AccommodationTypeRepository;
import com.str.backend.registration.dto.RegistrationExternalRequest;
import com.str.backend.registration.dto.RegistrationRequest;
import com.str.backend.request.SubmissionRepository;
import com.str.backend.rn.RnEntity;
import com.str.backend.rn.RnRepository;
import com.str.backend.rn.RnService;
import com.str.backend.str.FacilityClaimVerifier;
import com.str.backend.str.StrLessorLookupService;
import com.str.backend.validation.ParallelValidationOrchestrator;
import com.str.backend.validation.PipelineResult;
import com.str.backend.validation.ValidationContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Kontakt sa zahtjeva mora završiti na {@code lessor} retku (stavke 11-12 sa sastanka
 * 10.09.2026.). Ključno je da se upiše <b>prije</b> pohrane: {@code lessor.email} je
 * {@code updatable = false}, pa ga se nakon INSERT-a više ne može popuniti.
 */
class RegistrationServiceContactTest {

    private static final String OIB = "12312312316";

    private LessorRepository lessorRepository;
    private StrLessorLookupService strLessorLookupService;
    private RnService rnService;
    private RegistrationService service;

    @BeforeEach
    void setUp() {
        lessorRepository = mock(LessorRepository.class);
        AccommodationRepository accommodationRepository = mock(AccommodationRepository.class);
        SubmissionRepository submissionRepository = mock(SubmissionRepository.class);
        ParallelValidationOrchestrator orchestrator = mock(ParallelValidationOrchestrator.class);
        rnService = mock(RnService.class);
        RnRepository rnRepository = mock(RnRepository.class);
        strLessorLookupService = mock(StrLessorLookupService.class);
        CountyRepository countyRepository = mock(CountyRepository.class);
        MunicipalityRepository municipalityRepository = mock(MunicipalityRepository.class);
        SettlementRepository settlementRepository = mock(SettlementRepository.class);
        AccommodationTypeRepository accommodationTypeRepository = mock(AccommodationTypeRepository.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

        lenient().when(accommodationTypeRepository.existsById(anyLong())).thenReturn(true);

        service = new RegistrationService(
                lessorRepository, accommodationRepository, submissionRepository,
                orchestrator, rnService, rnRepository, strLessorLookupService,
                countyRepository, municipalityRepository, settlementRepository,
                accommodationTypeRepository, mock(FacilityClaimVerifier.class),
                new CadastreResolver(mock(HouseNumberRepository.class)), eventPublisher);

        when(countyRepository.findById(7L))
                .thenReturn(Optional.of(buildCounty(7L, "Splitsko-dalmatinska županija")));
        when(orchestrator.execute(any(ValidationContext.class))).thenReturn(PipelineResult.passed());
        lenient().when(rnRepository.findActiveOrSuspendedRnByAddressAndOib(
                anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(List.of());

        RnEntity issued = mock(RnEntity.class);
        lenient().when(issued.getRn()).thenReturn("HR120001000000000123");
        lenient().when(rnService.issue(any(UUID.class), any(UUID.class))).thenReturn(issued);
    }

    /**
     * NIAS put: {@code StrLessorLookupService} gradi iznajmljivača iz {@code str.subject*}, gdje
     * kontakta nema — e-mail dolazi isključivo sa zahtjeva.
     */
    @Test
    void niasFlow_contactFromRequest_isStoredOnLessor() {
        when(strLessorLookupService.resolveLessor(anyString())).thenReturn(lessorWithoutContact());

        service.generateRegistrationNumber(request("ana@example.com", "0991234567", "021555666", "Ana Anić"));

        LessorEntity saved = savedLessor();
        assertThat(saved.getEmail()).isEqualTo("ana@example.com");
        assertThat(saved.getMobileNumber()).isEqualTo("0991234567");
        assertThat(saved.getPhoneNumber()).isEqualTo("021555666");
        assertThat(saved.getContactName()).isEqualTo("Ana Anić");
    }

    /** Prazan string iz forme znači „nije upisano", ne prazna vrijednost. */
    @Test
    void niasFlow_blankOptionalContact_storedAsNull() {
        when(strLessorLookupService.resolveLessor(anyString())).thenReturn(lessorWithoutContact());

        service.generateRegistrationNumber(request("ana@example.com", "0991234567", "   ", ""));

        LessorEntity saved = savedLessor();
        assertThat(saved.getPhoneNumber()).isNull();
        assertThat(saved.getContactName()).isNull();
    }

    /**
     * Non-EU put: iznajmljivač je već pohranjen i e-mail je identitet računa
     * ({@code updatable = false}), pa se ne smije pregaziti sa zahtjeva.
     */
    @Test
    void externalFlow_keepsAccountEmail_updatesOnlyContactDetails() {
        LessorEntity postojeci = LessorEntity.create("PERO", "PERIĆ", "Ilica", "1",
                "Zagreb", "Grad Zagreb", "racun@example.com");
        postojeci.setLessorOib(OIB);
        UUID lessorId = postojeci.getLessorId();
        when(lessorRepository.findById(lessorId)).thenReturn(Optional.of(postojeci));

        service.generateRegistrationNumberExternal(
                externalRequest("drugi@example.com", "0912223334"), lessorId);

        assertThat(postojeci.getEmail()).isEqualTo("racun@example.com");
        assertThat(postojeci.getMobileNumber()).isEqualTo("0912223334");
    }

    /**
     * Neobavezna kontakt polja koja nisu poslana ne smiju obrisati zatečenu vrijednost —
     * mobitel se, kao obavezan, uvijek upisuje.
     */
    @Test
    void externalFlow_optionalFieldsNotSent_keepExistingValues() {
        LessorEntity postojeci = LessorEntity.create("PERO", "PERIĆ", "Ilica", "1",
                "Zagreb", "Grad Zagreb", "racun@example.com");
        postojeci.setLessorOib(OIB);
        postojeci.setContact("Pero Perić", "021111222", "0995556667", null);
        UUID lessorId = postojeci.getLessorId();
        when(lessorRepository.findById(lessorId)).thenReturn(Optional.of(postojeci));

        service.generateRegistrationNumberExternal(externalRequest(null, "0911112222"), lessorId);

        assertThat(postojeci.getContactName()).isEqualTo("Pero Perić");
        assertThat(postojeci.getPhoneNumber()).isEqualTo("021111222");
        assertThat(postojeci.getMobileNumber()).isEqualTo("0911112222");
    }

    /**
     * Rupa koju ovo zatvara: samoregistracija non-EU iznajmljivača traži e-mail, ali telefon je
     * ondje neobavezan, pa je iznajmljivač mogao ostati bez ijednog broja. Zahtjev za RB je
     * jedino mjesto na kojem se to popravlja.
     */
    @Test
    void externalFlow_lessorWithoutAnyPhone_getsMobileFromRequest() {
        LessorEntity bezBroja = LessorEntity.create("PERO", "PERIĆ", "Ilica", "1",
                "Zagreb", "Grad Zagreb", "racun@example.com");
        bezBroja.setLessorOib(OIB);
        UUID lessorId = bezBroja.getLessorId();
        when(lessorRepository.findById(lessorId)).thenReturn(Optional.of(bezBroja));

        assertThat(bezBroja.getMobileNumber()).isNull();

        service.generateRegistrationNumberExternal(externalRequest(null, "0912223334"), lessorId);

        assertThat(bezBroja.getMobileNumber()).isEqualTo("0912223334");
        assertThat(bezBroja.getEmail()).isEqualTo("racun@example.com");
    }

    private LessorEntity savedLessor() {
        ArgumentCaptor<LessorEntity> captor = ArgumentCaptor.forClass(LessorEntity.class);
        verify(lessorRepository).save(captor.capture());
        return captor.getValue();
    }

    /** Kakav ga vrati lookup u produkciji: bez e-maila i bez telefona. */
    private LessorEntity lessorWithoutContact() {
        LessorEntity lessor = LessorEntity.create("ANA", "ANIĆ", "Marulićeva", "5",
                "Split", "Splitsko-dalmatinska županija", null);
        lessor.setLessorOib(OIB);
        return lessor;
    }

    private RegistrationRequest request(String email, String mobitel, String telefon, String osoba) {
        return new RegistrationRequest(
                OIB, "AP1", null,
                7L, "Split", "Meje",
                "Marulićeva", "5", null, "21000",
                4,
                OfferType.PRIMARY_RESIDENCE, Offering.WHOLE,
                false, null, false, true,
                null, null, null, null, null, null, null,
                email, mobitel, telefon, osoba, null);
    }

    private RegistrationExternalRequest externalRequest(String email, String mobitel) {
        return new RegistrationExternalRequest(
                "AP1", null,
                7L, "Split", "Meje",
                "Marulićeva", "5", null, "21000",
                4,
                OfferType.PRIMARY_RESIDENCE, Offering.WHOLE,
                false, null, false, true,
                null, null, null, null, null, null, null,
                email, mobitel, null, null, null);
    }

    private CountyEntity buildCounty(Long id, String name) {
        try {
            var ctor = CountyEntity.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            CountyEntity c = ctor.newInstance();
            Field idField = CountyEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(c, id);
            Field nameField = CountyEntity.class.getDeclaredField("name");
            nameField.setAccessible(true);
            nameField.set(c, name);
            return c;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
