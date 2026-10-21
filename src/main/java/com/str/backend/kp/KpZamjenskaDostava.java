package com.str.backend.kp;

import com.str.backend.egop.EgopFilingStore;
import com.str.backend.egop.EgopPismenoEntity;
import com.str.backend.email.AktDostavaMailer;
import com.str.backend.email.MailRetryEntity;
import com.str.backend.email.MailRetryStore;
import com.str.backend.rn.RnEntity;
import com.str.backend.rn.RnRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Akt koji SZP nije mogao dostaviti jer primatelj nema aktivan korisnički pretinac (K006/Z005)
 * dostavlja se e-poštom ({@link AktDostavaMailer}). Neuspjelo slanje ide u {@link MailRetryStore}
 * pod {@link MailRetryEntity.Kind#KP_ZAMJENSKA}, a ponavlja ga {@code MailRetryJob} preko
 * {@link #retry}.
 *
 * <p>Ne baca iznimke: poziva se iz toka otpreme, čiji je ishod već zapisan.
 */
@Component
public class KpZamjenskaDostava {

    private static final Logger log = LoggerFactory.getLogger(KpZamjenskaDostava.class);

    private final KpOtpremaStore store;
    private final EgopFilingStore filingStore;
    private final RnRepository rnRepository;
    private final AktDostavaMailer mailer;
    private final MailRetryStore retryStore;

    public KpZamjenskaDostava(KpOtpremaStore store,
                              EgopFilingStore filingStore,
                              RnRepository rnRepository,
                              AktDostavaMailer mailer,
                              MailRetryStore retryStore) {
        this.store = store;
        this.filingStore = filingStore;
        this.rnRepository = rnRepository;
        this.mailer = mailer;
        this.retryStore = retryStore;
    }

    public void dostavi(UUID otpremaId) {
        try {
            deliver(otpremaId);
        } catch (RuntimeException e) {
            log.error("kp_zamjenska_error otprema={}: {}", otpremaId, e.getMessage(), e);
            retryStore.recordFailure(MailRetryEntity.Kind.KP_ZAMJENSKA, otpremaId, "error: " + e.getMessage());
        }
    }

    /** Ponovni pokušaj za {@code MailRetryJob}. */
    public void retry(UUID otpremaId) {
        deliver(otpremaId);
    }

    private void deliver(UUID otpremaId) {
        KpOtpremaEntity otprema = store.find(otpremaId).orElse(null);
        EgopPismenoEntity pismeno = otprema == null ? null : filingStore.findAkt(otprema.getPismenoId()).orElse(null);
        if (otprema == null || pismeno == null || pismeno.getPdfContent() == null) {
            log.error("kp_zamjenska_skipped reason=missing otprema={}", otpremaId);
            retryStore.abandon(MailRetryEntity.Kind.KP_ZAMJENSKA, otpremaId, "missing");
            return;
        }
        if (otprema.getStatus() != KpOtpremaEntity.Status.NEMA_PRETINCA) {
            retryStore.abandon(MailRetryEntity.Kind.KP_ZAMJENSKA, otpremaId, "not_nema_pretinca");
            return;
        }
        AktDostavaMailer.Ishod ishod = EgopPismenoEntity.ACT_REF_REGISTRACIJA.equals(pismeno.getActRef())
                ? mailer.dostaviDodjelu(rn(pismeno), pismeno.getPdfContent())
                : mailer.dostaviAkt(UUID.fromString(pismeno.getActRef()), pismeno.getPdfContent());
        switch (ishod) {
            case SENT -> {
                retryStore.markSent(MailRetryEntity.Kind.KP_ZAMJENSKA, otpremaId);
                log.info("kp_zamjenska_sent otprema={} pismeno={} rn={} vrsta='{}'", otpremaId, pismeno.getId(),
                        pismeno.getRn(), pismeno.getVrstaPismenaNaziv());
            }
            case FAILED -> retryStore.recordFailure(MailRetryEntity.Kind.KP_ZAMJENSKA, otpremaId, "smtp_failed");
            case NO_EMAIL -> {
                // Akt nije dostavljen ni u KP ni e-poštom — stranci ga mora dostaviti službenik.
                log.error("kp_zamjenska_no_email otprema={} pismeno={} rn={} vrsta='{}' — akt NIJE dostavljen;"
                                + " potrebna dostava drugim putem", otpremaId, pismeno.getId(), pismeno.getRn(),
                        pismeno.getVrstaPismenaNaziv());
                retryStore.abandon(MailRetryEntity.Kind.KP_ZAMJENSKA, otpremaId, "no_email");
            }
            case MISSING -> retryStore.abandon(MailRetryEntity.Kind.KP_ZAMJENSKA, otpremaId, "missing_detail");
        }
    }

    private String rn(EgopPismenoEntity pismeno) {
        if (pismeno.getRn() != null) {
            return pismeno.getRn();
        }
        return rnRepository.findBySubmissionId(pismeno.getSubmissionId()).stream()
                .findFirst().map(RnEntity::getRn).orElse(null);
    }
}
