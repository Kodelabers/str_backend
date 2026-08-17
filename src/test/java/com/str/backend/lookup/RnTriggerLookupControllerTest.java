package com.str.backend.lookup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.str.backend.document.DocumentLabels;
import com.str.backend.domain.RnStatus;
import com.str.backend.domain.RnTrigger;
import com.str.backend.lookup.RnTriggerLookupController.RnStatusReasonResponse;
import com.str.backend.lookup.RnTriggerLookupController.RnTriggerResponse;
import com.str.backend.lookup.RnTriggerLookupController.TriggerContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Popis razloga koji se nudi frontendu mora se poklapati s onim što servis stvarno prihvaća —
 * inače korisnik dobije 400 na razlog koji mu je sustav sam ponudio. Šifre su iz koda (invariant
 * testovi ispod), a natpis/redoslijed/aktivnost iz tablice {@code rn_status_reason} (mockana).
 */
class RnTriggerLookupControllerTest {

    private final DocumentLabels labels = new DocumentLabels();
    private final RnStatusReasonRepository repo = mock(RnStatusReasonRepository.class);
    private final RnTriggerLookupController controller = new RnTriggerLookupController(labels, repo);

    private static RnStatusReasonEntity row(String ctx, RnTrigger t, String label,
                                            boolean note, boolean active, int order) {
        return RnStatusReasonEntity.of(ctx, t.name(), label, note, active, order);
    }

    private static List<RnStatusReasonEntity> suspensionSeed() {
        return List.of(
                row("SUSPENZIJA", RnTrigger.CONSENT_EXPIRY, "istek suglasnosti suvlasnika", false, true, 1),
                row("SUSPENZIJA", RnTrigger.INSPECTION, "nalaz inspekcijskog nadzora", false, true, 2),
                row("SUSPENZIJA", RnTrigger.INCOMPLETE_DOCUMENTATION, "nepotpuna dokumentacija", false, true, 3),
                row("SUSPENZIJA", RnTrigger.OTHER, "drugi razlog", true, true, 4));
    }

    private static List<RnStatusReasonEntity> withdrawalSeed() {
        return List.of(row("POVLACENJE", RnTrigger.WITHDRAWAL, "povlačenje registracijskog broja", false, true, 1));
    }

    @BeforeEach
    void seed() {
        when(repo.findByContext("SUSPENZIJA")).thenReturn(suspensionSeed());
        when(repo.findByContext("POVLACENJE")).thenReturn(withdrawalSeed());
    }

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

    /** Redoslijed dolazi iz tablice (sort_order), ne iz enum poretka. */
    @Test
    void order_comesFromTableSortOrder() {
        when(repo.findByContext("SUSPENZIJA")).thenReturn(List.of(
                row("SUSPENZIJA", RnTrigger.OTHER, "drugi razlog", true, true, 1),
                row("SUSPENZIJA", RnTrigger.CONSENT_EXPIRY, "istek suglasnosti suvlasnika", false, true, 2),
                row("SUSPENZIJA", RnTrigger.INSPECTION, "nalaz inspekcijskog nadzora", false, true, 3),
                row("SUSPENZIJA", RnTrigger.INCOMPLETE_DOCUMENTATION, "nepotpuna dokumentacija", false, true, 4)));

        assertThat(body(TriggerContext.SUSPENZIJA))
                .extracting(RnTriggerResponse::sifra)
                .containsExactly("OTHER", "CONSENT_EXPIRY", "INSPECTION", "INCOMPLETE_DOCUMENTATION");
    }

    /** Neaktivan redak u tablici skriva razlog iz obrasca (admin toggle). */
    @Test
    void inactiveRow_isHiddenFromForm() {
        when(repo.findByContext("SUSPENZIJA")).thenReturn(List.of(
                row("SUSPENZIJA", RnTrigger.CONSENT_EXPIRY, "istek suglasnosti suvlasnika", false, true, 1),
                row("SUSPENZIJA", RnTrigger.INSPECTION, "nalaz inspekcijskog nadzora", false, false, 2),
                row("SUSPENZIJA", RnTrigger.INCOMPLETE_DOCUMENTATION, "nepotpuna dokumentacija", false, true, 3),
                row("SUSPENZIJA", RnTrigger.OTHER, "drugi razlog", true, true, 4)));

        assertThat(body(TriggerContext.SUSPENZIJA))
                .extracting(RnTriggerResponse::sifra)
                .containsExactly("CONSENT_EXPIRY", "INCOMPLETE_DOCUMENTATION", "OTHER")
                .doesNotContain("INSPECTION");
    }

    /** Ako razlogu fali redak u tablici, ne nestaje — natpis se uzme iz enum labela (fallback). */
    @Test
    void missingRow_fallsBackToEnumLabel() {
        when(repo.findByContext("SUSPENZIJA")).thenReturn(List.of(
                row("SUSPENZIJA", RnTrigger.CONSENT_EXPIRY, "istek suglasnosti suvlasnika", false, true, 1),
                row("SUSPENZIJA", RnTrigger.INSPECTION, "nalaz inspekcijskog nadzora", false, true, 2),
                row("SUSPENZIJA", RnTrigger.OTHER, "drugi razlog", true, true, 4)));

        List<RnTriggerResponse> body = body(TriggerContext.SUSPENZIJA);

        assertThat(body).extracting(RnTriggerResponse::sifra)
                .contains("INCOMPLETE_DOCUMENTATION");
        assertThat(body).filteredOn(r -> r.sifra().equals("INCOMPLETE_DOCUMENTATION"))
                .singleElement()
                .satisfies(r -> assertThat(r.naziv()).isEqualTo(labels.trigger(RnTrigger.INCOMPLETE_DOCUMENTATION)));
    }

    @Test
    void responseSerializesToExpectedJsonShape() throws Exception {
        String json = new ObjectMapper().writeValueAsString(body(TriggerContext.SUSPENZIJA));

        assertThat(json)
                .contains("\"sifra\":\"CONSENT_EXPIRY\"")
                .contains("\"naziv\":\"istek suglasnosti suvlasnika\"")
                .contains("\"traziBiljesku\":false")
                .contains("\"traziBiljesku\":true");
    }

    /** Puni katalog (uključivo neaktivne) za interni pregled/izvoz. */
    @Test
    void reasons_returnsFullCatalogIncludingInactive() {
        when(repo.findAllByOrderByContextAscSortOrderAsc()).thenReturn(Stream
                .concat(withdrawalSeed().stream(), Stream.of(
                        row("SUSPENZIJA", RnTrigger.INSPECTION, "nalaz inspekcijskog nadzora", false, false, 2)))
                .toList());

        List<RnStatusReasonResponse> body = controller.reasons().getBody();

        assertThat(body).extracting(RnStatusReasonResponse::sifra)
                .contains("WITHDRAWAL", "INSPECTION");
        assertThat(body).filteredOn(r -> r.sifra().equals("INSPECTION"))
                .singleElement()
                .satisfies(r -> assertThat(r.aktivan()).isFalse());
    }
}
