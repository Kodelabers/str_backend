package com.str.backend.lookup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.str.backend.document.DocumentLabels;
import com.str.backend.domain.RnStatus;
import com.str.backend.domain.RnTrigger;
import com.str.backend.lookup.RnTriggerLookupController.RnTriggerResponse;
import com.str.backend.lookup.RnTriggerLookupController.TriggerContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Popis razloga koji se nudi frontendu mora se poklapati s onim što servis stvarno prihvaća —
 * inače korisnik dobije 400 na razlog koji mu je sustav sam ponudio.
 */
class RnTriggerLookupControllerTest {

    private final DocumentLabels labels = new DocumentLabels();
    private final RnTriggerLookupController controller = new RnTriggerLookupController(labels);

    private List<RnTriggerResponse> body(TriggerContext context) {
        return controller.triggers(context).getBody();
    }

    @Test
    void suspensionReasons_matchWhatTheStateMachineAccepts() {
        List<RnTrigger> accepted = List.of(RnTrigger.values()).stream()
                .filter(t -> RnStatus.ACTIVE.canTransitionTo(RnStatus.SUSPENSION_PROPOSED, t))
                .toList();

        assertThat(RnTriggerLookupController.SUSPENSION_REASONS)
                .containsExactlyInAnyOrderElementsOf(accepted);
    }

    @Test
    void withdrawalReason_matchesWhatTheStateMachineAccepts() {
        List<RnTrigger> accepted = List.of(RnTrigger.values()).stream()
                .filter(t -> RnStatus.ACTIVE.canTransitionTo(RnStatus.WITHDRAWN, t))
                .toList();

        assertThat(RnTriggerLookupController.WITHDRAWAL_REASONS)
                .containsExactlyInAnyOrderElementsOf(accepted);
    }

    /** Natpis se traži po ključu i baca ako ga nema — bolje pasti u testu nego pri renderu akta. */
    @Test
    void everyOfferedReason_hasCroatianLabel() {
        for (TriggerContext context : TriggerContext.values()) {
            assertThat(body(context)).isNotEmpty().allSatisfy(r ->
                    assertThat(r.naziv()).isNotBlank().isNotEqualTo(r.sifra()));
        }
    }

    @Test
    void onlyOther_requiresNote() {
        assertThat(body(TriggerContext.SUSPENZIJA))
                .filteredOn(RnTriggerResponse::traziBiljesku)
                .extracting(RnTriggerResponse::sifra)
                .containsExactly(RnTrigger.OTHER.name());
    }

    @Test
    void defaultsToSuspensionContext() {
        assertThat(body(TriggerContext.SUSPENZIJA))
                .extracting(RnTriggerResponse::sifra)
                .containsExactly("CONSENT_EXPIRY", "INSPECTION", "INCOMPLETE_DOCUMENTATION", "OTHER");
    }

    /**
     * Odgovor je paketno-privatan record; Jackson takve serijalizira preko {@code setAccessible},
     * ali to je pretpostavka koju vrijedi zabiti — ispravan popis koji se ne može serijalizirati
     * pao bi tek na prvom pozivu s fronta, a ne u buildu.
     */
    @Test
    void responseSerializesToExpectedJsonShape() throws Exception {
        String json = new ObjectMapper().writeValueAsString(body(TriggerContext.SUSPENZIJA));

        assertThat(json)
                .contains("\"sifra\":\"CONSENT_EXPIRY\"")
                .contains("\"naziv\":\"istek suglasnosti suvlasnika\"")
                .contains("\"traziBiljesku\":false")
                .contains("\"traziBiljesku\":true");
    }
}
