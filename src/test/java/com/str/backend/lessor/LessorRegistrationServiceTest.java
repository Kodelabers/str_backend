package com.str.backend.lessor;

import com.str.backend.address.CountryEntity;
import com.str.backend.address.CountryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LessorRegistrationServiceTest {

    private LessorRepository lessorRepository;
    private LessorDocumentRepository documentRepository;
    private PasswordEncoder passwordEncoder;
    private CountryRepository countryRepository;
    private LessorRegistrationService service;

    @BeforeEach
    void setUp() {
        lessorRepository = mock(LessorRepository.class);
        documentRepository = mock(LessorDocumentRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        countryRepository = mock(CountryRepository.class);
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");
        // By default every referenced country exists and is active.
        CountryEntity activeCountry = mock(CountryEntity.class);
        when(activeCountry.isActive()).thenReturn(true);
        when(countryRepository.findById(anyLong())).thenReturn(Optional.of(activeCountry));
        service = new LessorRegistrationService(
                lessorRepository, documentRepository, passwordEncoder, countryRepository);
    }

    @Test
    void register_happyPath_savesLessorAndDocumentAndReturnsUsername() throws IOException {
        when(lessorRepository.findByEmail("john@example.com")).thenReturn(Optional.empty());

        LessorRegistrationResponse response = service.register(validRequest());

        ArgumentCaptor<LessorEntity> lessorCaptor = ArgumentCaptor.forClass(LessorEntity.class);
        verify(lessorRepository).save(lessorCaptor.capture());
        LessorEntity saved = lessorCaptor.getValue();

        assertThat(saved.getFirstName()).isEqualTo("John");
        assertThat(saved.getLastName()).isEqualTo("Doe");
        assertThat(saved.getEmail()).isEqualTo("john@example.com");
        assertThat(saved.getUsername()).isEqualTo("john@example.com");

        verify(documentRepository).save(any(LessorDocumentEntity.class));
        verify(passwordEncoder).encode("StrongPassw0rd!");

        assertThat(response.lessorId()).isEqualTo(saved.getLessorId());
        assertThat(response.username()).isEqualTo("john@example.com");
    }

    @Test
    void register_normalizesEmailToLowercase() throws IOException {
        when(lessorRepository.findByEmail("john@example.com")).thenReturn(Optional.empty());

        LessorRegistrationRequest req = validRequest();
        req.setEmail("  John@Example.COM  ");

        LessorRegistrationResponse response = service.register(req);

        assertThat(response.username()).isEqualTo("john@example.com");
    }

    @Test
    void register_passwordMismatch_throws400_beforeAnyWrite() {
        LessorRegistrationRequest req = validRequest();
        req.setPasswordPotvrda("DifferentPassword!");

        assertThatThrownBy(() -> service.register(req))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode().value())
                .isEqualTo(400);

        verify(lessorRepository, never()).save(any());
        verify(documentRepository, never()).save(any());
    }

    @Test
    void register_duplicateEmail_throwsGeneric400_beforeAnyWrite() {
        when(lessorRepository.findByEmail("john@example.com"))
                .thenReturn(Optional.of(mock(LessorEntity.class)));

        assertThatThrownBy(() -> service.register(validRequest()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode().value())
                .isEqualTo(400);

        verify(lessorRepository, never()).save(any());
        verify(documentRepository, never()).save(any());
    }

    @Test
    void register_emptyFrontImage_throws400_beforeAnyWrite() throws IOException {
        when(lessorRepository.findByEmail("john@example.com")).thenReturn(Optional.empty());

        LessorRegistrationRequest req = validRequest();
        req.setIspravaPrednja(new MockMultipartFile("ispravaPrednja", new byte[0]));

        assertThatThrownBy(() -> service.register(req))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode().value())
                .isEqualTo(400);

        verify(lessorRepository, never()).save(any());
        verify(documentRepository, never()).save(any());
    }

    @Test
    void register_backImageAbsent_savesDocumentWithNullBack() throws IOException {
        when(lessorRepository.findByEmail("john@example.com")).thenReturn(Optional.empty());

        LessorRegistrationRequest req = validRequest();
        req.setIspravaStraznja(null);

        service.register(req);

        ArgumentCaptor<LessorDocumentEntity> captor = ArgumentCaptor.forClass(LessorDocumentEntity.class);
        verify(documentRepository).save(captor.capture());
        assertThat(captor.getValue().getBackImage()).isNull();
    }

    @Test
    void register_backImageEmpty_savesDocumentWithNullBack() throws IOException {
        when(lessorRepository.findByEmail("john@example.com")).thenReturn(Optional.empty());

        LessorRegistrationRequest req = validRequest();
        req.setIspravaStraznja(new MockMultipartFile("ispravaStraznja", new byte[0]));

        service.register(req);

        ArgumentCaptor<LessorDocumentEntity> captor = ArgumentCaptor.forClass(LessorDocumentEntity.class);
        verify(documentRepository).save(captor.capture());
        assertThat(captor.getValue().getBackImage()).isNull();
    }

    @Test
    void register_documentFieldsMapped() throws IOException {
        when(lessorRepository.findByEmail("john@example.com")).thenReturn(Optional.empty());

        service.register(validRequest());

        ArgumentCaptor<LessorDocumentEntity> captor = ArgumentCaptor.forClass(LessorDocumentEntity.class);
        verify(documentRepository).save(captor.capture());
        LessorDocumentEntity doc = captor.getValue();

        assertThat(doc.getDocumentType()).isEqualTo("PASSPORT");
        assertThat(doc.getDocumentNumber()).isEqualTo("AB123456");
        assertThat(doc.getFrontImage()).isEqualTo(new byte[]{1, 2, 3});
        assertThat(doc.getUploadedAt()).isNotNull();
    }

    @Test
    void register_legalEntityOwner_mapsGroupToEntity() throws IOException {
        when(lessorRepository.findByEmail("john@example.com")).thenReturn(Optional.empty());

        LessorRegistrationRequest req = validRequest();
        req.setVlasnikJePravnaOsoba(true);
        req.setNazivPravneOsobe("  Acme Holdings Ltd  ");
        req.setDrzavaSjedistaId(380);
        req.setGradSjedista("  Milano  ");
        req.setMaticniBrojPravneOsobe("  MI-12345678  ");

        service.register(req);

        ArgumentCaptor<LessorEntity> captor = ArgumentCaptor.forClass(LessorEntity.class);
        verify(lessorRepository).save(captor.capture());
        LessorEntity saved = captor.getValue();

        assertThat(saved.isLegalEntityOwner()).isTrue();
        assertThat(saved.getLegalEntityName()).isEqualTo("Acme Holdings Ltd");
        assertThat(saved.getLegalEntityCountryId()).isEqualTo(380);
        assertThat(saved.getLegalEntityCity()).isEqualTo("Milano");
        assertThat(saved.getLegalEntityRegistrationNumber()).isEqualTo("MI-12345678");
    }

    @Test
    void register_residenceCountryNotFound_throws422_beforeAnyWrite() {
        when(lessorRepository.findByEmail("john@example.com")).thenReturn(Optional.empty());
        when(countryRepository.findById(999L)).thenReturn(Optional.empty());

        LessorRegistrationRequest req = validRequest();
        req.setZemljaPrebivalistaId(999);

        assertThatThrownBy(() -> service.register(req))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode().value())
                .isEqualTo(422);

        verify(lessorRepository, never()).save(any());
        verify(documentRepository, never()).save(any());
    }

    @Test
    void register_residenceCountryInactive_throws422() {
        when(lessorRepository.findByEmail("john@example.com")).thenReturn(Optional.empty());
        CountryEntity inactive = mock(CountryEntity.class);
        when(inactive.isActive()).thenReturn(false);
        when(countryRepository.findById(2L)).thenReturn(Optional.of(inactive));

        LessorRegistrationRequest req = validRequest();
        req.setZemljaPrebivalistaId(2);

        assertThatThrownBy(() -> service.register(req))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode().value())
                .isEqualTo(422);

        verify(lessorRepository, never()).save(any());
    }

    @Test
    void register_residenceCountryIsEuMember_throws422_beforeAnyWrite() {
        when(lessorRepository.findByEmail("john@example.com")).thenReturn(Optional.empty());
        CountryEntity euCountry = mock(CountryEntity.class);
        when(euCountry.isActive()).thenReturn(true);
        when(euCountry.getIso2Alpha()).thenReturn("DE");   // EU member -> rejected
        when(countryRepository.findById(11L)).thenReturn(Optional.of(euCountry));

        LessorRegistrationRequest req = validRequest();
        req.setZemljaPrebivalistaId(11);

        assertThatThrownBy(() -> service.register(req))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode().value())
                .isEqualTo(422);

        verify(lessorRepository, never()).save(any());
        verify(documentRepository, never()).save(any());
    }

    @Test
    void register_legalEntitySeatCountryIsEuMember_throws422_beforeAnyWrite() {
        when(lessorRepository.findByEmail("john@example.com")).thenReturn(Optional.empty());
        CountryEntity euCountry = mock(CountryEntity.class);
        when(euCountry.isActive()).thenReturn(true);
        when(euCountry.getIso2Alpha()).thenReturn("IT");   // EU member -> rejected
        when(countryRepository.findById(380L)).thenReturn(Optional.of(euCountry));

        LessorRegistrationRequest req = validRequest();   // residence country (1) stays valid via default stub
        req.setVlasnikJePravnaOsoba(true);
        req.setNazivPravneOsobe("Acme Holdings Ltd");
        req.setDrzavaSjedistaId(380);
        req.setGradSjedista("Milano");
        req.setMaticniBrojPravneOsobe("MI-12345678");

        assertThatThrownBy(() -> service.register(req))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode().value())
                .isEqualTo(422);

        verify(lessorRepository, never()).save(any());
        verify(documentRepository, never()).save(any());
    }

    @Test
    void register_legalEntitySeatCountryNotFound_throws422_beforeAnyWrite() {
        when(lessorRepository.findByEmail("john@example.com")).thenReturn(Optional.empty());
        when(countryRepository.findById(999L)).thenReturn(Optional.empty());

        LessorRegistrationRequest req = validRequest();   // residence country (1) stays valid via default stub
        req.setVlasnikJePravnaOsoba(true);
        req.setNazivPravneOsobe("Acme Holdings Ltd");
        req.setDrzavaSjedistaId(999);
        req.setGradSjedista("Milano");
        req.setMaticniBrojPravneOsobe("MI-12345678");

        assertThatThrownBy(() -> service.register(req))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode().value())
                .isEqualTo(422);

        verify(lessorRepository, never()).save(any());
        verify(documentRepository, never()).save(any());
    }

    @Test
    void register_legalEntityFlagTrueWithMissingField_throws422_beforeAnyWrite() {
        when(lessorRepository.findByEmail("john@example.com")).thenReturn(Optional.empty());

        LessorRegistrationRequest req = validRequest();
        req.setVlasnikJePravnaOsoba(true);
        req.setNazivPravneOsobe("Acme Holdings Ltd");
        req.setDrzavaSjedistaId(null);          // missing — must not NPE
        req.setGradSjedista("Milano");
        req.setMaticniBrojPravneOsobe("MI-12345678");

        assertThatThrownBy(() -> service.register(req))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode().value())
                .isEqualTo(422);

        verify(lessorRepository, never()).save(any());
        verify(documentRepository, never()).save(any());
    }

    @Test
    void register_withoutLegalEntity_leavesGroupUnset() throws IOException {
        when(lessorRepository.findByEmail("john@example.com")).thenReturn(Optional.empty());

        service.register(validRequest());

        ArgumentCaptor<LessorEntity> captor = ArgumentCaptor.forClass(LessorEntity.class);
        verify(lessorRepository).save(captor.capture());
        LessorEntity saved = captor.getValue();

        assertThat(saved.isLegalEntityOwner()).isFalse();
        assertThat(saved.getLegalEntityName()).isNull();
        assertThat(saved.getLegalEntityCountryId()).isNull();
        assertThat(saved.getLegalEntityCity()).isNull();
        assertThat(saved.getLegalEntityRegistrationNumber()).isNull();
    }

    private LessorRegistrationRequest validRequest() {
        LessorRegistrationRequest req = new LessorRegistrationRequest();
        req.setIme("John");
        req.setPrezime("Doe");
        req.setDatumRodjenja(LocalDate.of(1985, 6, 15));
        req.setPorezniBroj("12345678");
        req.setZemljaPrebivalistaId(1);
        req.setStalnaAdresa("123 Main St, Springfield");
        req.setVrstaIsprave("PASSPORT");
        req.setBrojIsprave("AB123456");
        req.setEmail("john@example.com");
        req.setTelefon("+385912345678");
        req.setPassword("StrongPassw0rd!");
        req.setPasswordPotvrda("StrongPassw0rd!");
        req.setIspravaPrednja(
                new MockMultipartFile("ispravaPrednja", "front.jpg", "image/jpeg", new byte[]{1, 2, 3}));
        return req;
    }
}
