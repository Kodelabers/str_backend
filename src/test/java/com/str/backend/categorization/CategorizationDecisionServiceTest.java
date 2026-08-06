package com.str.backend.categorization;

import com.str.backend.exception.BusinessException;
import com.str.backend.lookup.AccommodationTypeEntity;
import com.str.backend.lookup.AccommodationTypeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CategorizationDecisionServiceTest {

    private static final String OIB = "99999999990";
    private static final byte[] PDF = "%PDF-1.7\n...".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00};

    private final CategorizationDecisionRepository repository = mock(CategorizationDecisionRepository.class);
    private final AccommodationTypeRepository typeRepository = mock(AccommodationTypeRepository.class);
    private final CategorizationDecisionService service =
            new CategorizationDecisionService(repository, typeRepository);

    @Test
    void stores_pdfWithSubmittedStatus() {
        CategorizationDecisionEntity saved = capture(request(file("rjesenje.pdf", "application/pdf", PDF)));

        assertThat(saved.getLessorOib()).isEqualTo(OIB);
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
        verify(repository, never()).save(any());
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

    @Test
    void rejects_whenAccommodationTypeCodeUnknown() {
        when(typeRepository.findByCodeIgnoreCase(anyString())).thenReturn(Optional.empty());
        CategorizationDecisionRequest req = request(file("rjesenje.pdf", "application/pdf", PDF));
        req.setVrstaSifra("FS_NEPOSTOJECA");

        assertThatThrownBy(() -> service.upload(OIB, req))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.accommodation.type.unknown");
    }

    @Test
    void keepsMetadata_whenSupplied() {
        when(typeRepository.findByCodeIgnoreCase("FS_SOBA"))
                .thenReturn(Optional.of(new AccommodationTypeEntity("Soba", true, "Privatni smještaj")));
        CategorizationDecisionRequest req = request(file("rjesenje.pdf", "application/pdf", PDF));
        req.setNazivObjekta("  Soba Marija  ");
        req.setVrstaSifra("FS_SOBA");
        req.setBrKreveta(3);
        req.setNapomena("   ");

        CategorizationDecisionEntity saved = capture(req);

        assertThat(saved.getObjectName()).isEqualTo("Soba Marija");
        assertThat(saved.getAccommodationTypeCode()).isEqualTo("FS_SOBA");
        assertThat(saved.getMaxBeds()).isEqualTo(3);
        assertThat(saved.getNote()).isNull();
    }

    /** Klijent može poslati putanju u nazivu datoteke; sprema se samo naziv. */
    @Test
    void stripsPathFromFileName() {
        MockMultipartFile withPath = new MockMultipartFile(
                "datoteka", "C:\\Users\\pero\\Desktop\\rjesenje.pdf", "application/pdf", PDF);

        assertThat(capture(request(withPath)).getFileName()).isEqualTo("rjesenje.pdf");
    }

    private CategorizationDecisionEntity capture(CategorizationDecisionRequest req) {
        when(repository.save(any(CategorizationDecisionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        service.upload(OIB, req);

        org.mockito.ArgumentCaptor<CategorizationDecisionEntity> captor =
                org.mockito.ArgumentCaptor.forClass(CategorizationDecisionEntity.class);
        verify(repository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }

    private static CategorizationDecisionRequest request(MockMultipartFile file) {
        CategorizationDecisionRequest req = new CategorizationDecisionRequest();
        req.setDatoteka(file);
        return req;
    }

    private static MockMultipartFile file(String name, String contentType, byte[] content) {
        return new MockMultipartFile("datoteka", name, contentType, content);
    }
}
