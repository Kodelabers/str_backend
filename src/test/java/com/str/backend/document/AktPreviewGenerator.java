package com.str.backend.document;

import com.str.backend.domain.OfferType;
import com.str.backend.domain.Offering;
import com.str.backend.domain.RnStatus;
import com.str.backend.email.EmailTemplates;
import com.str.backend.email.MailTemplate;
import com.str.backend.email.MailProperties;
import com.str.backend.email.MailTemplateLoader;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.lessor.LessorRepository;
import com.str.backend.pdf.SubmissionPdfContext;
import com.str.backend.pdf.SubmissionPdfGenerator;
import com.str.backend.request.SubmissionRepository;
import com.str.backend.rn.RegistrationNumberLogRepository;
import com.str.backend.rn.RnRepository;
import com.str.backend.rn.dto.RnDetailDto;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Nije test nego generator uzoraka: renderira svaki akt i svaku poruku u {@code target/preview}
 * s realnim podacima, da se izgled može pogledati bez podizanja aplikacije.
 */
class AktPreviewGenerator {

    private static final String RN = "HR180000123456789001";
    private static final Path OUT = Path.of("target", "preview");

    private static final DocumentProperties PROPS = new DocumentProperties(
            new DocumentProperties.Tijelo(
                    "MINISTARSTVO TURIZMA I SPORTA", "87892589782",
                    "Prisavlje 14", "Zagreb", "Uprava za turizam",
                    "članka 12. Zakona o pružanju usluga u turizmu"),
            new DocumentProperties.Potpisnik("Ivana Ivić", "Voditeljica postupka"),
            new DocumentProperties.Epecat(false, ""),
            Map.of(
                    "suspenzija", "nije dopuštena žalba, ali se može pokrenuti upravni spor tužbom"
                            + " Upravnom sudu u Zagrebu u roku od 30 dana od dana dostave ove Obavijesti.",
                    "povlacenje", "nije dopuštena žalba, ali se može pokrenuti upravni spor tužbom"
                            + " Upravnom sudu u Zagrebu u roku od 30 dana od dana dostave ove Obavijesti."),
            false);

    @Disabled("Alat, ne test — pokrenuti ručno: mvn test -Dtest=AktPreviewGenerator nakon izmjene predloška")
    @Test
    void generate() throws IOException {
        Files.createDirectories(OUT);
        akti();
        zahtjev();
        System.out.println("PREVIEW OK -> " + OUT.toAbsolutePath());
    }

    private void akti() throws IOException {
        RnRepository rnRepository = mock(RnRepository.class);
        LessorRepository lessorRepository = mock(LessorRepository.class);
        SubmissionRepository submissionRepository = mock(SubmissionRepository.class);
        RegistrationNumberLogRepository logRepository = mock(RegistrationNumberLogRepository.class);

        when(rnRepository.findDetail(RN)).thenReturn(Optional.of(detail()));
        when(lessorRepository.findById(any())).thenReturn(Optional.of(lessor()));
        when(submissionRepository.findById(any())).thenReturn(Optional.empty());
        when(logRepository.findFirstByRnOrderByOccurredAtDesc(RN)).thenReturn(Optional.empty());

        ZupTemplateLoader loader = new ZupTemplateLoader(PROPS);
        loader.loadAll();
        DocumentLabels labels = new DocumentLabels();
        StrDocumentService service = new StrDocumentService(rnRepository, lessorRepository,
                submissionRepository, logRepository, loader, new ZupContextFactory(PROPS, labels),
                new ZupDocumentRenderer(PROPS), labels);

        for (StrDocumentType type : StrDocumentType.templateBackedTypes()) {
            String razlog = switch (type) {
                case SUSPENZIJA, PRIJEDLOG_SUSPENZIJE -> "istek suglasnosti suvlasnika";
                case POVLACENJE -> "utvrđeno oglašavanje bez važećeg registracijskog broja";
                case PRIGOVOR -> "suglasnost suvlasnika je pribavljena i dostavlja se u privitku";
                default -> null;
            };
            byte[] pdf = service.render(type, RN, razlog);
            Files.write(OUT.resolve(type.slug() + ".pdf"), pdf);
        }
    }

    private void zahtjev() throws IOException {
        SubmissionPdfGenerator generator = new SubmissionPdfGenerator(PROPS);
        var accommodation = mock(com.str.backend.accommodation.AccommodationEntity.class);
        when(accommodation.getName()).thenReturn("Apartman Sunce");
        when(accommodation.getStreet()).thenReturn("Ilica");
        when(accommodation.getStreetNumber()).thenReturn("1");
        when(accommodation.getPostalCode()).thenReturn("10000");
        when(accommodation.getCity()).thenReturn("Zagreb");
        when(accommodation.getMaxBeds()).thenReturn(4);

        byte[] pdf = generator.generate(SubmissionPdfContext.of(accommodation, lessor(),
                "Apartman", RN, "KLASA: 334-01/26-01/55, URBROJ: 529-06/26-1"));
        Files.write(OUT.resolve("zahtjev.pdf"), pdf);
    }

    private static RnDetailDto detail() {
        return new RnDetailDto(RN, RnStatus.SUSPENDED,
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 1), null,
                LocalDate.of(2026, 8, 15), null, null, UUID.randomUUID(),
                UUID.randomUUID(), "Grad Zagreb", "Zagreb", null, "Ilica", "1",
                "Apartman Sunce", "Apartman", 4, "3*",
                UUID.randomUUID(), "Ana", "Anić", null, "ana.anic@example.com", "98765432109",
                false, null, null, null,
                null, null, null, null, null);
    }

    private static LessorEntity lessor() {
        LessorEntity l = LessorEntity.create("Ana", "Anić", "Ilica", "1", "Zagreb", "Grad Zagreb",
                "ana.anic@example.com");
        l.setLessorOib("98765432109");
        l.setContactName("Ana Anić");
        l.setPhoneNumber("01 2345 678");
        l.setMobileNumber("098 123 4567");
        return l;
    }
}
