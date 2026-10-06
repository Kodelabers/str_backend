package com.str.backend.categorization;

import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.accommodation.AccommodationRepository;
import com.str.backend.domain.RnStatus;
import com.str.backend.exception.BusinessException;
import com.str.backend.exception.ConflictException;
import com.str.backend.exception.ResourceNotFoundException;
import com.str.backend.lookup.AccommodationTypeEntity;
import com.str.backend.lookup.AccommodationTypeRepository;
import com.str.backend.rn.RnEntity;
import com.str.backend.rn.RnRepository;
import com.str.backend.str.StrFacilityRepository;
import com.str.backend.str.StrFacilityRepository.FacilityOwnershipRow;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CategorizationDecisionServiceTest {

    private static final String OIB = "99999999990";
    private static final String RN = "HR120001000000000123";
    private static final UUID ACCOMMODATION_ID = UUID.randomUUID();
    private static final byte[] PDF = "%PDF-1.7\n...".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00};

    private final CategorizationDecisionRepository repository = mock(CategorizationDecisionRepository.class);
    private final AccommodationTypeRepository typeRepository = mock(AccommodationTypeRepository.class);
    private final RnRepository rnRepository = mock(RnRepository.class);
    private final AccommodationRepository accommodationRepository = mock(AccommodationRepository.class);
    private final StrFacilityRepository facilityRepository = mock(StrFacilityRepository.class);
    private final CategorizationDecisionService service = new CategorizationDecisionService(
            repository, typeRepository, rnRepository, accommodationRepository, facilityRepository);

    private final RnEntity rn = mock(RnEntity.class);
    private final AccommodationEntity accommodation = mock(AccommodationEntity.class);

    @BeforeEach
    void ownedActiveRnOfNewObject() {
        when(rnRepository.isOwnedByOib(RN, OIB)).thenReturn(true);
        when(rnRepository.findById(RN)).thenReturn(Optional.of(rn));
        when(rn.getAccommodationId()).thenReturn(ACCOMMODATION_ID);
        when(rn.getStatus()).thenReturn(RnStatus.ACTIVE);
        when(accommodationRepository.findById(ACCOMMODATION_ID)).thenReturn(Optional.of(accommodation));
        when(accommodation.getFacilityId()).thenReturn(null);
        when(repository.existsByRnAndStatusIn(eq(RN), anyCollection())).thenReturn(false);
    }

    @Test
    void stores_pdfWithSubmittedStatus_linkedToRn() {
        CategorizationDecisionEntity saved = capture(request(file("rjesenje.pdf", "application/pdf", PDF)));

        assertThat(saved.getLessorOib()).isEqualTo(OIB);
        assertThat(saved.getRn()).isEqualTo(RN);
        assertThat(saved.getContentType()).isEqualTo("application/pdf");
        assertThat(saved.getFileName()).isEqualTo("rjesenje.pdf");
        assertThat(saved.getFileSize()).isEqualTo(PDF.length);
        assertThat(saved.getStatus()).isEqualTo(CategorizationDecisionStatus.SUBMITTED);
        assertThat(saved.getFacilityId()).isNull();
    }

    @Test
    void accepts_pngAndJpeg() {
        assertThat(capture(request(file("skan.png", "image/png", PNG))).getContentType()).isEqualTo("image/png");
        assertThat(capture(request(file("skan.jpg", "image/jpeg", JPEG))).getContentType()).isEqualTo("image/jpeg");
    }

    /** Nadležno tijelo vidi objekt bez otvaranja skena — podaci dolaze iz smještaja RB-a. */
    @Test
    void copiesMetadata_fromAccommodationOfRn() {
        when(accommodation.getName()).thenReturn("  Soba Marija  ");
        when(accommodation.getAccommodationTypeId()).thenReturn(7L);
        when(accommodation.getStreet()).thenReturn("Kraljevska");
        when(accommodation.getStreetNumber()).thenReturn("88");
        when(accommodation.getPostalCode()).thenReturn("21300");
        when(accommodation.getCity()).thenReturn("Makarska");
        when(accommodation.getMaxBeds()).thenReturn(3);
        AccommodationTypeEntity type = mock(AccommodationTypeEntity.class);
        when(type.getCode()).thenReturn("FS_SOBA");
        when(typeRepository.findById(7L)).thenReturn(Optional.of(type));

        CategorizationDecisionEntity saved = capture(request(file("rjesenje.pdf", "application/pdf", PDF)));

        assertThat(saved.getObjectName()).isEqualTo("Soba Marija");
        assertThat(saved.getAccommodationTypeCode()).isEqualTo("FS_SOBA");
        assertThat(saved.getAddressText()).isEqualTo("Kraljevska 88, 21300 Makarska");
        assertThat(saved.getMaxBeds()).isEqualTo(3);
        assertThat(saved.getDecisionNumber()).isNull();
        assertThat(saved.getNote()).isNull();
    }

    /** Tuđi i nepostojeći RB izgledaju isto — 404, da se ne otkriva postoji li. */
    @Test
    void rejects_whenRnNotOwned() {
        when(rnRepository.isOwnedByOib(RN, OIB)).thenReturn(false);

        // Poruka je ključ, ne ulaz korisnika — ista za tuđi i nepostojeći RB.
        assertThatThrownBy(() -> service.upload(OIB, request(file("rjesenje.pdf", "application/pdf", PDF))))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("error.rn.notFound");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void rejects_whenRnBlank() {
        CategorizationDecisionRequest req = request(file("rjesenje.pdf", "application/pdf", PDF));
        req.setRegistrationNumber("   ");

        assertThatThrownBy(() -> service.upload(OIB, req)).isInstanceOf(ResourceNotFoundException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    /** Verificiran objekt iz eTurizma kategorizaciju već ima — rješenje uz njegov RB nema smisla. */
    @Test
    void rejects_whenAccommodationComesFromETurizam() {
        when(accommodation.getFacilityId()).thenReturn("153049");
        facilityVerified(153049L, true);

        assertConflict("CATEGORIZATION_NOT_REQUIRED");
    }

    /** Neverificiran (migriran) objekt: podaci nisu provjereni, pa iznajmljivač smije priložiti rješenje. */
    @Test
    void stores_whenAccommodationIsUnverifiedETurizamFacility() {
        when(accommodation.getFacilityId()).thenReturn("153049");
        facilityVerified(153049L, false);

        CategorizationDecisionEntity saved = capture(request(file("rjesenje.pdf", "application/pdf", PDF)));

        assertThat(saved.getRn()).isEqualTo(RN);
        assertThat(saved.getStatus()).isEqualTo(CategorizationDecisionStatus.SUBMITTED);
    }

    /** Objekt koji eTurizam ne pronađe, ni onaj bez autora, nije dokazano neverificiran. */
    @Test
    void rejects_whenETurizamFacilityNotFoundOrVerificationUnknown() {
        when(accommodation.getFacilityId()).thenReturn("153049");
        when(facilityRepository.findOwnership(153049L)).thenReturn(Optional.empty());
        assertConflict("CATEGORIZATION_NOT_REQUIRED");

        facilityVerified(153049L, null);
        assertConflict("CATEGORIZATION_NOT_REQUIRED");
    }

    /** Neverificiran objekt ne zaobilazi ostale provjere: status RB-a i jedno aktivno rješenje. */
    @Test
    void unverifiedFacility_stillChecksRnStatusAndActiveDecision() {
        when(accommodation.getFacilityId()).thenReturn("153049");
        facilityVerified(153049L, false);

        when(rn.getStatus()).thenReturn(RnStatus.WITHDRAWN);
        assertConflict("CATEGORIZATION_RN_STATUS");

        when(rn.getStatus()).thenReturn(RnStatus.ACTIVE);
        when(repository.existsByRnAndStatusIn(eq(RN), anyCollection())).thenReturn(true);
        assertConflict("CATEGORIZATION_ALREADY_SUBMITTED");
    }

    /** Vlasništvo prije svega: za tuđi RB eTurizam se ni ne pita. */
    @Test
    void rejects_unownedRn_beforeAskingETurizam() {
        when(rnRepository.isOwnedByOib(RN, OIB)).thenReturn(false);
        when(accommodation.getFacilityId()).thenReturn("153049");
        facilityVerified(153049L, false);

        assertThatThrownBy(() -> service.upload(OIB, request(file("rjesenje.pdf", "application/pdf", PDF))))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(facilityRepository, never()).findOwnership(anyLong());
    }

    @Test
    void rejects_whenFacilityIdNotNumeric() {
        when(accommodation.getFacilityId()).thenReturn("nije-broj");

        assertConflict("CATEGORIZATION_NOT_REQUIRED");
    }

    private void facilityVerified(long facilityId, Boolean verified) {
        FacilityOwnershipRow row = mock(FacilityOwnershipRow.class);
        when(row.getVerified()).thenReturn(verified);
        when(facilityRepository.findOwnership(facilityId)).thenReturn(Optional.of(row));
    }

    @Test
    void rejects_whenRnWithdrawn() {
        when(rn.getStatus()).thenReturn(RnStatus.WITHDRAWN);

        assertConflict("CATEGORIZATION_RN_STATUS");
    }

    /** Predaja rješenja je odgovor na suspenziju zbog nepotpune dokumentacije — mora proći. */
    @Test
    void accepts_whenRnSuspendedOrProposedForSuspension() {
        when(rn.getStatus()).thenReturn(RnStatus.SUSPENDED);
        assertThat(capture(request(file("rjesenje.pdf", "application/pdf", PDF))).getRn()).isEqualTo(RN);

        when(rn.getStatus()).thenReturn(RnStatus.SUSPENSION_PROPOSED);
        assertThat(capture(request(file("rjesenje.pdf", "application/pdf", PDF))).getRn()).isEqualTo(RN);
    }

    /** Jedno aktivno rješenje po RB-u; odbijeno ne blokira (upit gleda samo SUBMITTED i VERIFIED). */
    @Test
    void rejects_whenActiveDecisionAlreadyExists() {
        when(repository.existsByRnAndStatusIn(eq(RN), anyCollection())).thenReturn(true);

        assertConflict("CATEGORIZATION_ALREADY_SUBMITTED");
        assertThat(CategorizationDecisionService.ACTIVE_DECISION_STATUSES)
                .containsExactlyInAnyOrder(CategorizationDecisionStatus.SUBMITTED, CategorizationDecisionStatus.VERIFIED);
    }

    /**
     * Tip se određuje iz sadržaja: klijent koji pošalje .exe s Content-Type: application/pdf
     * ne smije proći, jer datoteku kasnije otvara nadležno tijelo.
     */
    @Test
    void rejects_whenContentTypeHeaderLiesAboutContent() {
        MockMultipartFile fake = file("virus.pdf", "application/pdf",
                "MZ\u0090not really a pdf".getBytes(StandardCharsets.ISO_8859_1));

        assertThatThrownBy(() -> service.upload(OIB, request(fake)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.categorization.file.type");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void rejects_whenFileMissingOrEmpty() {
        assertThatThrownBy(() -> service.upload(OIB, request(null)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.categorization.file.empty");

        assertThatThrownBy(() -> service.upload(OIB, request(file("prazno.pdf", "application/pdf", new byte[0]))))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.categorization.file.empty");
    }

    /** Klijent može poslati putanju u nazivu datoteke; sprema se samo naziv. */
    @Test
    void stripsPathFromFileName() {
        MockMultipartFile withPath = new MockMultipartFile(
                "datoteka", "C:\\Users\\pero\\Desktop\\rjesenje.pdf", "application/pdf", PDF);

        assertThat(capture(request(withPath)).getFileName()).isEqualTo("rjesenje.pdf");
    }

    /**
     * Dvije istodobne predaje prođu provjeru, a drugu odbije unique index. Mora stići kao
     * „već predano", ne kao generički sukob podataka.
     */
    @Test
    void mapsActiveIndexViolation_toAlreadySubmitted() {
        when(repository.saveAndFlush(any(CategorizationDecisionEntity.class))).thenThrow(new DataIntegrityViolationException(
                "dup", new ConstraintViolationException("dup", new SQLException("dup", "23505"),
                        CategorizationDecisionService.ACTIVE_DECISION_INDEX)));

        assertThatThrownBy(() -> service.upload(OIB, request(file("rjesenje.pdf", "application/pdf", PDF))))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getCode()).isEqualTo("CATEGORIZATION_ALREADY_SUBMITTED"));
    }

    @Test
    void otherIntegrityViolations_propagate() {
        DataIntegrityViolationException fk = new DataIntegrityViolationException(
                "fk", new ConstraintViolationException("fk", new SQLException("fk", "23503"),
                        "fk_categorization_decision_rn"));
        when(repository.saveAndFlush(any(CategorizationDecisionEntity.class))).thenThrow(fk);

        assertThatThrownBy(() -> service.upload(OIB, request(file("rjesenje.pdf", "application/pdf", PDF))))
                .isSameAs(fk);
    }

    /** Nazivi na kojima je `Paths` bacao iznimku (500) daju zamjenski naziv. */
    @Test
    void safeFileName_handlesDegenerateNames() {
        assertThat(CategorizationDecisionService.safeFileName("/")).isEqualTo("rjesenje");
        assertThat(CategorizationDecisionService.safeFileName("a\0b.pdf")).isEqualTo("ab.pdf");
        assertThat(CategorizationDecisionService.safeFileName("C:\\x\\..\\r.pdf")).isEqualTo("r.pdf");
        assertThat(CategorizationDecisionService.safeFileName("  ")).isEqualTo("rjesenje");
    }

    private void assertConflict(String code) {
        assertThatThrownBy(() -> service.upload(OIB, request(file("rjesenje.pdf", "application/pdf", PDF))))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getCode()).isEqualTo(code));
        verify(repository, never()).saveAndFlush(any());
    }

    private CategorizationDecisionEntity capture(CategorizationDecisionRequest req) {
        when(repository.saveAndFlush(any(CategorizationDecisionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        service.upload(OIB, req);

        ArgumentCaptor<CategorizationDecisionEntity> captor = ArgumentCaptor.forClass(CategorizationDecisionEntity.class);
        verify(repository, atLeastOnce()).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    private static CategorizationDecisionRequest request(MockMultipartFile file) {
        CategorizationDecisionRequest req = new CategorizationDecisionRequest();
        req.setDatoteka(file);
        req.setRegistrationNumber(RN);
        return req;
    }

    private static MockMultipartFile file(String name, String contentType, byte[] content) {
        return new MockMultipartFile("datoteka", name, contentType, content);
    }
}
