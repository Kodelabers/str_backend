package com.str.backend.email;

import com.str.backend.document.DocumentLabels;
import com.str.backend.document.StrDocumentService;
import com.str.backend.document.StrDocumentType;
import com.str.backend.domain.RnStatus;
import com.str.backend.domain.RnTrigger;
import com.str.backend.rn.RnLifecycleLookup;
import com.str.backend.rn.dto.RnDetailDto;
import com.str.backend.rn.event.RnLifecycleEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * Šalje iznajmljivaču obavijest o promjeni statusa registracijskog broja.
 *
 * <p>Radi AFTER_COMMIT, pa vidi i podatke koje pozivatelj postavi nakon prijelaza — npr.
 * {@code suspensionDeadline}, koji {@code RnService#suspend} upisuje tek nakon
 * {@code transition(...)}.
 *
 * <p>Mail <b>nije dostava</b> (čl. 94. ZUP-a); akt se dostavlja u korisnički pretinac i od
 * te dostave teku rokovi. Zato se akt prilaže kao preslika, a svaki predložak nosi klauzulu
 * koja to izrijekom kaže.
 */
@Component
public class RnLifecycleEmailListener {

    private static final Logger log = LoggerFactory.getLogger(RnLifecycleEmailListener.class);

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy.");

    private final RnLifecycleLookup lookup;
    private final StrDocumentService documentService;
    private final DocumentLabels labels;
    private final EmailService emailService;

    public RnLifecycleEmailListener(RnLifecycleLookup lookup,
                                    StrDocumentService documentService,
                                    DocumentLabels labels,
                                    EmailService emailService) {
        this.lookup = lookup;
        this.documentService = documentService;
        this.labels = labels;
        this.emailService = emailService;
    }

    /**
     * <b>Namjerno bez {@code @Transactional}.</b> Slanje ide na SMTP poslužitelj; transakcija
     * na ovoj metodi držala bi konekciju otvorenom kroz to mrežno čekanje. Čitanja idu kroz
     * {@link RnLifecycleLookup} i {@link StrDocumentService}, koji imaju vlastite kratke
     * transakcije s {@code REQUIRES_NEW} — što ujedno rješava i to da je u {@code AFTER_COMMIT}
     * fazi izvorna transakcija već dovršena.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onLifecycleChange(RnLifecycleEvent event) {
        Optional<StrDocumentType> akt = StrDocumentType.forTransition(
                event.to(), event.trigger(), event.initiatedByLessor());
        if (akt.isEmpty()) {
            return;
        }
        MailTemplate template = mailTemplateFor(akt.get());

        RnDetailDto detail = lookup.findDetail(event.rn()).orElse(null);
        if (detail == null) {
            log.error("rn_lifecycle_mail_skipped reason=missing_detail rn={}", event.rn());
            return;
        }
        if (detail.lessorEmail() == null || detail.lessorEmail().isBlank()) {
            log.warn("rn_lifecycle_mail_skipped reason=no_email rn={} template={}",
                    event.rn(), template);
            return;
        }

        String razlog = razlog(event);
        emailService.sendRnLifecycleNotification(new RnLifecycleMail(
                template,
                detail.lessorEmail(),
                ime(detail),
                event.rn(),
                objekt(detail),
                razlog,
                rok(detail),
                aktPdf(akt.get(), event.rn(), razlog),
                // Non-EU iznajmljivač nema OIB pa ni pristup korisničkom pretincu — njemu je
                // e-pošta kanal dostave, a ne samo obavijest. Klauzula se po tome razlikuje.
                detail.lessorOib() == null || detail.lessorOib().isBlank()));
    }

    private static MailTemplate mailTemplateFor(StrDocumentType type) {
        return switch (type) {
            case SUSPENZIJA -> MailTemplate.SUSPENZIJA;
            case POVLACENJE -> MailTemplate.POVLACENJE;
            case OPOZIV -> MailTemplate.OPOZIV;
            case REAKTIVACIJA -> MailTemplate.REAKTIVACIJA;
            case PRIJEDLOG_SUSPENZIJE -> MailTemplate.PRIJEDLOG_SUSPENZIJE;
            // ZAHTJEV/DODJELA/PRIGOVOR ne nastaju iz prijelaza statusa.
            default -> throw new IllegalStateException("Nema poruke za vrstu akta " + type);
        };
    }

    /**
     * Akt uz obavijest. Pad rendera ne smije spriječiti mail — status je već promijenjen i
     * iznajmljivač o tome mora saznati, makar bez privitka.
     */
    private byte[] aktPdf(StrDocumentType type, String rn, String razlog) {
        try {
            return documentService.render(type, rn, razlog);
        } catch (RuntimeException e) {
            log.error("rn_lifecycle_pdf_failed rn={} type={}: {} — mail ide bez privitka",
                    rn, type, e.getMessage(), e);
            return null;
        }
    }

    private String razlog(RnLifecycleEvent event) {
        if (event.reason() != null && !event.reason().isBlank()) {
            return event.reason().strip();
        }
        return labels.trigger(event.trigger());
    }

    private String rok(RnDetailDto detail) {
        return detail.suspensionDeadline() == null
                ? labels.get("rok.default")
                : labels.format("rok.doDatuma", detail.suspensionDeadline().format(DATE));
    }

    private String ime(RnDetailDto detail) {
        if (detail.lessorFirstName() != null && !detail.lessorFirstName().isBlank()) {
            return detail.lessorFirstName();
        }
        return detail.lessorLegalEntityName() == null ? "" : detail.lessorLegalEntityName();
    }

    private static String objekt(RnDetailDto detail) {
        String naziv = detail.accommodationName();
        String adresa = ((detail.street() == null ? "" : detail.street()) + " "
                + (detail.streetNumber() == null ? "" : detail.streetNumber())).strip();
        if (naziv == null || naziv.isBlank()) {
            return adresa;
        }
        return adresa.isEmpty() ? naziv : naziv + ", " + adresa;
    }
}
