package com.str.backend.lookup;

import com.str.backend.document.DocumentLabels;
import com.str.backend.domain.RnTrigger;
import com.str.backend.domain.WithdrawalReason;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Razlozi promjene statusa registracijskog broja, s hrvatskim natpisima.
 *
 * <p>Postoji jer je frontend dosad taj popis hardkodirao: {@link RnTrigger} je Java enum bez
 * tablice i bez ijednog endpointa, pa se popis u obrascu suspenzije nije mogao razlikovati od
 * onoga što {@code RnService.suspend} stvarno prihvaća — dok se ne bi razišao i korisnik dobio
 * 400 na razlog koji mu je ponuđen.
 *
 * <p>Natpisi se čitaju iz {@link DocumentLabels}, dakle iz iste datoteke iz koje ih uzimaju
 * akti i mailovi ({@code documents/hr/labels.properties}). Nema drugog popisa koji bi se mogao
 * razići s ovim.
 *
 * <p><b>Ovo nije šifrarnik u bazi.</b> Naručitelj je tražio popis razloga na pregled i dopunu, a
 * kasnije i formu za administriranje. Sama tablica se može dodati, ali semantika prijelaza ostaje
 * u kodu ({@code RnStatus.canTransitionTo}, {@code StrDocumentType.forTransition} → koji se akt po
 * ZUP-u generira), pa bi se iz baze mogli administrirati natpis i aktivnost, a ne i novi razlog.
 * Vidi {@code docs/RN-RAZLOZI-STATUSA.md}.
 */
@RestController
@RequestMapping("/api/lookups")
class RnTriggerLookupController {

    /** Razlozi koje {@code RnService.suspend} prihvaća — isti popis, ne kopija. */
    static final List<RnTrigger> SUSPENSION_REASONS = RnTrigger.SUSPENSION_REASONS;

    /**
     * Razlozi povlačenja (MINTS), za prijedlog povlačenja (T12). Prijelaz nosi okidač
     * {@link RnTrigger#WITHDRAWAL_PROPOSAL}, a razlog je zasebna šifra koju {@code RnService.proposeWithdrawal}
     * pretvara u tekst akta. Namjerno bez
     * {@link RnTrigger#REVOCATION}: opoziv pokreće iznajmljivač sam, bez razloga i bez ovog
     * obrasca, pa ga službenik ne smije moći odabrati.
     */
    static final List<WithdrawalReason> WITHDRAWAL_REASONS = List.of(WithdrawalReason.values());

    private final DocumentLabels labels;

    RnTriggerLookupController(DocumentLabels labels) {
        this.labels = labels;
    }

    /** Kontekst u kojem se razlog nudi; ne postoji zajednički popis jer se obrasci razlikuju. */
    enum TriggerContext {
        SUSPENZIJA,
        POVLACENJE
    }

    record RnTriggerResponse(String sifra, String naziv, boolean traziBiljesku) {
    }

    @GetMapping("/rn-triggers")
    ResponseEntity<List<RnTriggerResponse>> triggers(
            @RequestParam(defaultValue = "SUSPENZIJA") TriggerContext kontekst) {
        List<RnTriggerResponse> body = switch (kontekst) {
            case SUSPENZIJA -> SUSPENSION_REASONS.stream()
                    .map(t -> new RnTriggerResponse(t.name(), labels.trigger(t), t == RnTrigger.OTHER))
                    .toList();
            case POVLACENJE -> WITHDRAWAL_REASONS.stream()
                    .map(r -> new RnTriggerResponse(r.name(), labels.withdrawalReason(r), r.requiresNote()))
                    .toList();
        };
        return ResponseEntity.ok(body);
    }
}
