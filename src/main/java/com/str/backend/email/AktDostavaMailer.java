package com.str.backend.email;

import com.str.backend.document.DocumentLabels;
import com.str.backend.document.StrDocumentType;
import com.str.backend.rn.RnLifecycleLookup;
import com.str.backend.rn.dto.RnDetailDto;
import com.str.backend.rn.event.RnLifecycleEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Dostava akta <b>e-poštom</b> stranci koja ima OIB, ali nema aktivan korisnički pretinac (SZP je
 * odbio poruku s K006/Z005). Odluka od 21. 10. 2026.: tada je e-pošta kanal dostave, kao za
 * non-EU iznajmljivača — isti predlošci, s klauzulom o dostavi e-poštom i aktom u privitku.
 *
 * <p>Obavijest koja je uz akt već otišla ({@link RnLifecycleEmailListener}, „akt se dostavlja u
 * pretinac") se ne povlači; ova poruka je zasebna dostava. Za razliku od te obavijesti, ovdje se
 * <b>ne</b> provjerava je li prijelaz u međuvremenu nadjačan: akt je izdan i mora biti dostavljen.
 */
@Component
public class AktDostavaMailer {

    private static final Logger log = LoggerFactory.getLogger(AktDostavaMailer.class);

    public enum Ishod { SENT, FAILED, NO_EMAIL, MISSING }

    private final RnLifecycleLookup lookup;
    private final DocumentLabels labels;
    private final EmailService emailService;

    public AktDostavaMailer(RnLifecycleLookup lookup, DocumentLabels labels, EmailService emailService) {
        this.lookup = lookup;
        this.labels = labels;
        this.emailService = emailService;
    }

    /** Obavijest o dodjeli registracijskog broja. */
    public Ishod dostaviDodjelu(String rn, byte[] akt) {
        Optional<RnDetailDto> detail = detail(rn);
        if (detail.isEmpty()) {
            return Ishod.MISSING;
        }
        RnDetailDto d = detail.get();
        if (blank(d.lessorEmail())) {
            return Ishod.NO_EMAIL;
        }
        boolean sent = emailService.sendRnIssuedNotification(new RnIssuedMail(
                d.lessorEmail(), RnLifecycleEmailListener.ime(d), rn, RnLifecycleEmailListener.objekt(d), akt, true));
        return sent ? Ishod.SENT : Ishod.FAILED;
    }

    /**
     * Akt životnog ciklusa; prijelaz se čita iz revizijskog zapisa ({@code actRef} akta je
     * {@code log_id}), da poruka nosi isti razlog i rok kao akt.
     */
    public Ishod dostaviAkt(UUID logId, byte[] akt) {
        RnLifecycleEvent event = lookup.findEvent(logId).orElse(null);
        if (event == null) {
            log.error("akt_dostava_mail_skipped reason=missing_log log={}", logId);
            return Ishod.MISSING;
        }
        Optional<StrDocumentType> vrsta = StrDocumentType.forTransition(event.to(), event.trigger());
        if (vrsta.isEmpty()) {
            log.error("akt_dostava_mail_skipped reason=no_act log={} rn={}", logId, event.rn());
            return Ishod.MISSING;
        }
        Optional<RnDetailDto> detail = detail(event.rn());
        if (detail.isEmpty()) {
            return Ishod.MISSING;
        }
        RnDetailDto d = detail.get();
        if (blank(d.lessorEmail())) {
            return Ishod.NO_EMAIL;
        }
        boolean sent = emailService.sendRnLifecycleNotification(new RnLifecycleMail(
                RnLifecycleEmailListener.mailTemplateFor(vrsta.get()),
                d.lessorEmail(),
                RnLifecycleEmailListener.ime(d),
                event.rn(),
                RnLifecycleEmailListener.objekt(d),
                RnLifecycleEmailListener.razlog(labels, event),
                RnLifecycleEmailListener.rok(labels, d),
                akt,
                true));
        return sent ? Ishod.SENT : Ishod.FAILED;
    }

    private Optional<RnDetailDto> detail(String rn) {
        Optional<RnDetailDto> detail = rn == null ? Optional.empty() : lookup.findDetail(rn);
        if (detail.isEmpty()) {
            log.error("akt_dostava_mail_skipped reason=missing_detail rn={}", rn);
        }
        return detail;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
