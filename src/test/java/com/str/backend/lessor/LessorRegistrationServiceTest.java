package com.str.backend.lessor;

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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LessorRegistrationServiceTest {

    private LessorRepository lessorRepository;
    private LessorDocumentRepository documentRepository;
    private PasswordEncoder passwordEncoder;
    private LessorRegistrationService service;

    @BeforeEach
    void setUp() {
        lessorRepository = mock(LessorRepository.class);
        documentRepository = mock(LessorDocumentRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");
        service = new LessorRegistrationService(lessorRepository, documentRepository, passwordEncoder);
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
