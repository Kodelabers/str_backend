package com.str.backend.lookup;

import com.str.backend.document.DocumentLabels;
import com.str.backend.domain.RnTrigger;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Razlozi promjene statusa registracijskog broja, s hrvatskim natpisima.
 *
 * <p>Postoji jer je frontend dosad taj popis hardkodirao: {@link RnTrigger} je Java enum, pa se
 * popis u obrascu suspenzije nije mogao razlikovati od onoga što {@code RnService.suspend} stvarno
 * prihvaća — dok se ne bi razišao i korisnik dobio 400 na razlog koji mu je ponuđen.
 *
 * <p><b>Podjela izvora istine.</b> KOJI su razlozi semantički valjani ostaje u kodu
 * ({@link #SUSPENSION_REASONS} / {@link #WITHDRAWAL_REASONS}, usklađeni s
 * {@code RnStatus.canTransitionTo}). Prezentacija — natpis, redoslijed, „traži bilješku",
 * aktivnost — dolazi iz tablice {@code str_rn.rn_status_reason} ({@link RnStatusReasonRepository}),
 * pa se može pregledati/dopuniti bez rebuilda. Presjek jamči da baza ne može uvesti razlog koji
 * kod ne prihvaća (nepoznata šifra u tablici se ignorira), a fallback na
 * {@link DocumentLabels} jamči da valjan razlog nikad ne nestane samo zato što mu redak fali.
 * Neaktivan redak ({@code active=false}) skriva razlog iz obrasca. Vidi {@code docs/RN-RAZLOZI-STATUSA.md}.
 */
@RestController
@RequestMapping("/api/lookups")
class RnTriggerLookupController {

    /** Razlozi koje {@code RnService.suspend} prihvaća — popis mora ostati u koraku s njim. */
    static final List<RnTrigger> SUSPENSION_REASONS = List.of(
            RnTrigger.CONSENT_EXPIRY,
            RnTrigger.INSPECTION,
            RnTrigger.INCOMPLETE_DOCUMENTATION,
            RnTrigger.OTHER);

    /** Povlačenje ima jedan razlog; slobodan tekst se šalje kao {@code reason}. */
    static final List<RnTrigger> WITHDRAWAL_REASONS = List.of(RnTrigger.WITHDRAWAL);

    private final DocumentLabels labels;
    private final RnStatusReasonRepository reasonRepository;

    RnTriggerLookupController(DocumentLabels labels, RnStatusReasonRepository reasonRepository) {
        this.labels = labels;
        this.reasonRepository = reasonRepository;
    }

    /** Kontekst u kojem se razlog nudi; ne postoji zajednički popis jer se obrasci razlikuju. */
    enum TriggerContext {
        SUSPENZIJA(SUSPENSION_REASONS),
        POVLACENJE(WITHDRAWAL_REASONS);

        private final List<RnTrigger> triggers;

        TriggerContext(List<RnTrigger> triggers) {
            this.triggers = triggers;
        }

        List<RnTrigger> triggers() {
            return triggers;
        }
    }

    record RnTriggerResponse(String sifra, String naziv, boolean traziBiljesku) {
    }

    /** Puni katalog razloga za interni pregled/izvoz (uključivo neaktivne), s administrabilnim poljima. */
    record RnStatusReasonResponse(Long id, String kontekst, String sifra, String naziv,
                                  boolean traziBiljesku, boolean aktivan, int redoslijed) {
    }

    @GetMapping("/rn-triggers")
    ResponseEntity<List<RnTriggerResponse>> triggers(
            @RequestParam(defaultValue = "SUSPENZIJA") TriggerContext kontekst) {
        Map<String, RnStatusReasonEntity> rowsByCode = reasonRepository.findByContext(kontekst.name())
                .stream()
                .collect(Collectors.toMap(RnStatusReasonEntity::getCode, Function.identity(), (a, b) -> a));

        List<RnTriggerResponse> body = kontekst.triggers().stream()
                .map(t -> Map.entry(t, Optional.ofNullable(rowsByCode.get(t.name()))))
                // Postojeći neaktivan redak skriva razlog; redak koji fali ne skriva (fallback).
                .filter(e -> e.getValue().map(RnStatusReasonEntity::isActive).orElse(true))
                .sorted(Comparator.comparingInt(e ->
                        e.getValue().map(RnStatusReasonEntity::getSortOrder).orElse(Integer.MAX_VALUE)))
                .map(e -> {
                    RnTrigger t = e.getKey();
                    Optional<RnStatusReasonEntity> row = e.getValue();
                    return new RnTriggerResponse(
                            t.name(),
                            row.map(RnStatusReasonEntity::getLabelHr).orElseGet(() -> labels.trigger(t)),
                            row.map(RnStatusReasonEntity::isRequiresNote).orElse(t == RnTrigger.OTHER));
                })
                .toList();
        return ResponseEntity.ok(body);
    }

    @GetMapping("/rn-status-reasons")
    ResponseEntity<List<RnStatusReasonResponse>> reasons() {
        List<RnStatusReasonResponse> body = reasonRepository.findAllByOrderByContextAscSortOrderAsc()
                .stream()
                .map(r -> new RnStatusReasonResponse(
                        r.getReasonId(), r.getContext(), r.getCode(), r.getLabelHr(),
                        r.isRequiresNote(), r.isActive(), r.getSortOrder()))
                .toList();
        return ResponseEntity.ok(body);
    }
}
