package com.str.backend.document;

import com.str.backend.domain.RnTrigger;
import com.str.backend.exception.ResourceNotFoundException;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.lessor.LessorRepository;
import com.str.backend.request.SubmissionRepository;
import com.str.backend.rn.RegistrationNumberLogEntity;
import com.str.backend.rn.RegistrationNumberLogRepository;
import com.str.backend.rn.RnRepository;
import com.str.backend.rn.dto.RnDetailDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;

/**
 * Generira akte životnog ciklusa registracijskog broja po strukturi iz čl. 98. ZUP-a.
 *
 * <p>Zamjenjuje raniji {@code RnDocumentService}, koji je akt ispisivao kao naslov, pet
 * label/value redaka i jedan odlomak proze — bez zaglavlja, uvoda, izreke, obrazloženja i
 * upute o pravnom lijeku.
 *
 * <p><b>Dostava nije ovdje.</b> Servis samo renderira PDF; dostava u korisnički pretinac
 * (koja po čl. 94. st. 6 ZUP-a jedina vrijedi kao osobna dostava i od koje teku rokovi) te
 * urudžbiranje u eGOP su zasebni koraci.
 */
@Service
public class StrDocumentService {

    private static final Logger log = LoggerFactory.getLogger(StrDocumentService.class);

    private final RnRepository rnRepository;
    private final LessorRepository lessorRepository;
    private final SubmissionRepository submissionRepository;
    private final RegistrationNumberLogRepository logRepository;
    private final ZupTemplateLoader templates;
    private final ZupContextFactory contextFactory;
    private final ZupDocumentRenderer renderer;
    private final DocumentLabels labels;

    public StrDocumentService(RnRepository rnRepository,
                              LessorRepository lessorRepository,
                              SubmissionRepository submissionRepository,
                              RegistrationNumberLogRepository logRepository,
                              ZupTemplateLoader templates,
                              ZupContextFactory contextFactory,
                              ZupDocumentRenderer renderer,
                              DocumentLabels labels) {
        this.rnRepository = rnRepository;
        this.lessorRepository = lessorRepository;
        this.submissionRepository = submissionRepository;
        this.logRepository = logRepository;
        this.templates = templates;
        this.contextFactory = contextFactory;
        this.renderer = renderer;
        this.labels = labels;
    }

    public byte[] render(StrDocumentType type, String rn, String razlog) {
        return render(type, rn, razlog, null);
    }

    /**
     * {@code REQUIRES_NEW} jer se ovo zove i iz {@code AFTER_COMMIT} konteksta
     * ({@code EgopRegistrationDispatcher} preko {@code RnIssuedListener}, i
     * {@code RnLifecycleEmailListener}): ondje je izvorna transakcija već dovršena, pa bi je
     * zadani {@code REQUIRED} pokušao nastaviti. Isti razlog stoji iza {@code REQUIRES_NEW}
     * na svakoj metodi {@code EgopFilingStore}.
     *
     * <p>Čitanja su kratka i idu prije rendera; sam PDF se sastavlja izvan upita, ali unutar
     * ove transakcije — riječ je o desecima milisekundi CPU-a, ne o mrežnom čekanju kakvo je
     * ranije držalo eGOP transakciju otvorenom.
     *
     * @param razlog slobodan tekst razloga; {@code null} → uzima se iz revizijskog traga
     * @param filing urudžbene oznake; {@code null} → KLASA se čita sa submissiona, URBROJ se
     *               izostavlja (akti suspenzijskog toka se još ne urudžbiraju — BX1/BX2/BX3)
     */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public byte[] render(StrDocumentType type, String rn, String razlog, FilingReference filing) {
        if (!type.templateBacked()) {
            throw new DocumentTemplateException(
                    "Vrsta akta " + type + " ima vlastiti generator i ne renderira se iz predloška.");
        }
        RnDetailDto detail = rnRepository.findDetail(rn)
                .orElseThrow(() -> new ResourceNotFoundException("rn not found: " + rn));

        FilingReference oznake = filing != null ? filing
                : new FilingReference(klasaZa(detail), null);

        Map<String, String> ctx =
                contextFactory.forRn(type, detail, razlog != null ? razlog : razlogIzTraga(rn), oznake);
        dopuniAdresu(ctx, detail);

        byte[] pdf = renderer.render(templates.get(type), ctx);
        log.info("document_rendered type={} rn={} klasa={} bytes={}",
                type, rn, oznake.klasa(), pdf.length);
        return pdf;
    }

    /** KLASA je na predmetu, a predmet na submissionu — dijele je svi akti istog RB-a. */
    private String klasaZa(RnDetailDto detail) {
        if (detail.submissionId() == null) {
            return null;
        }
        return submissionRepository.findById(detail.submissionId())
                .map(s -> s.getEgopKlasa())
                .orElse(null);
    }

    /**
     * Razlog iz zadnjeg zapisa u {@code registration_number_log}. {@code RnService.suspend}
     * ne prosljeđuje slobodan tekst, pa u praksi ostaje natpis okidača — što je i točnije od
     * proizvoljnog {@code ?reason=} parametra sa zahtjeva.
     */
    private String razlogIzTraga(String rn) {
        Optional<RegistrationNumberLogEntity> zadnji =
                logRepository.findFirstByRnOrderByOccurredAtDesc(rn);
        if (zadnji.isEmpty()) {
            return null;
        }
        RegistrationNumberLogEntity zapis = zadnji.get();
        if (zapis.getReason() != null && !zapis.getReason().isBlank()) {
            return zapis.getReason();
        }
        try {
            return labels.trigger(RnTrigger.valueOf(zapis.getTriggerName()));
        } catch (IllegalArgumentException e) {
            // Okidač iz starijeg zapisa koji više ne postoji u enumu — bolje ništa nego kriv razlog.
            log.warn("document_unknown_trigger rn={} trigger={}", rn, zapis.getTriggerName());
            return null;
        }
    }

    /**
     * Adresa prebivališta iznajmljivača nije na {@link RnDetailDto} i tamo se namjerno ne
     * dodaje — taj DTO se vraća i na {@code GET /api/rn/{rn}}, pa bi to proširilo izloženost
     * osobnih podataka izvan akta.
     */
    private void dopuniAdresu(Map<String, String> ctx, RnDetailDto detail) {
        if (detail.lessorId() == null) {
            return;
        }
        Optional<LessorEntity> lessor = lessorRepository.findById(detail.lessorId());
        lessor.ifPresent(l -> ZupContextFactory.putStrankaAdresa(
                ctx, l.getStreet(), l.getStreetNumber(), l.getPlace(), null));
    }
}
