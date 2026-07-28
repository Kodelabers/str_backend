package com.str.backend.egop;

import com.str.backend.document.StrDocumentService;
import com.str.backend.document.StrDocumentType;
import com.str.backend.domain.RnStatus;
import com.str.backend.domain.RnTrigger;
import com.str.backend.rn.RnLifecycleLookup;
import com.str.backend.rn.event.RnLifecycleEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RnLifecycleFilingListenerTest {

    private static final String RN = "HR180000123456789001";

    private RnLifecycleLookup lookup;
    private StrDocumentService documentService;
    private EgopFilingStore store;
    private EgopAktDispatcher dispatcher;
    private UUID submissionId;

    @BeforeEach
    void setUp() {
        lookup = mock(RnLifecycleLookup.class);
        documentService = mock(StrDocumentService.class);
        store = mock(EgopFilingStore.class);
        dispatcher = mock(EgopAktDispatcher.class);

        submissionId = UUID.randomUUID();
        when(lookup.findSubmissionId(RN)).thenReturn(Optional.of(submissionId));
        when(documentService.render(any(), any(), any())).thenReturn("pdf".getBytes());
        when(store.saveAkt(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    /** @param bezSifre slugovi na blokadnoj listi; prazno = sve se urudžbira */
    private RnLifecycleFilingListener listener(String... bezSifre) {
        return new RnLifecycleFilingListener(lookup, documentService, store, dispatcher,
                new EgopAktiBezSifre(Set.of(bezSifre)));
    }

    @Test
    void suspension_recordsActThenDispatches() {
        listener().onLifecycleChange(event(RnStatus.ACTIVE, RnStatus.SUSPENDED,
                RnTrigger.INSPECTION, null, "nalaz inspekcije"));

        EgopPismenoEntity akt = capturedAkt();
        assertThat(akt.getVrstaPismenaNaziv()).isEqualTo("Obavijest o suspenziji registracijskog broja");
        assertThat(akt.getSmjer()).isEqualTo(EgopPismenoEntity.Smjer.IZLAZNO);
        assertThat(akt.getRn()).isEqualTo(RN);
        assertThat(akt.getSubmissionId()).isEqualTo(submissionId);
        // Dokument mora biti snimljen prije slanja — retry ne smije renderirati iznova.
        assertThat(akt.getPdfContent()).isNotEmpty();
        assertThat(akt.isFiled()).isFalse();
        verify(dispatcher).dispatch(akt.getId());
    }

    /**
     * Poziv na izjašnjavanje prije nepovoljne odluke (čl. 30. st. 2). Prije uvođenja
     * dvofazne suspenzije ovaj prijelaz nije proizvodio ništa — stranka je saznala tek za
     * gotovu suspenziju, iako od dostave ovog akta teče rok za ispravak.
     */
    @Test
    void suspensionProposal_recordsAct() {
        listener().onLifecycleChange(event(RnStatus.ACTIVE, RnStatus.SUSPENSION_PROPOSED,
                RnTrigger.INCOMPLETE_DOCUMENTATION, null, null));

        EgopPismenoEntity akt = capturedAkt();
        assertThat(akt.getVrstaPismenaNaziv())
                .isEqualTo("Obavijest o prijedlogu suspenzije registracijskog broja");
        verify(dispatcher).dispatch(akt.getId());
    }

    /** Stranka je otklonila nedostatak — postupak se obustavlja i o tome mora biti obaviještena. */
    @Test
    void revokedProposal_recordsObustavaAct() {
        listener().onLifecycleChange(event(RnStatus.SUSPENSION_PROPOSED, RnStatus.ACTIVE,
                RnTrigger.REVOKE_PROPOSAL, null, null));

        assertThat(capturedAkt().getVrstaPismenaNaziv())
                .isEqualTo("Obavijest o obustavi postupka suspenzije registracijskog broja");
    }

    /** Opoziv i povlačenje dijele okidač; razlikuje ih tko je promjenu pokrenuo. */
    @Test
    void withdrawal_byLessor_filesRevocationAct() {
        listener().onLifecycleChange(event(RnStatus.ACTIVE, RnStatus.WITHDRAWN,
                RnTrigger.WITHDRAWAL, "LESSOR:" + UUID.randomUUID(), null));

        assertThat(capturedAkt().getVrstaPismenaNaziv())
                .isEqualTo("Obavijest o opozivu registracijskog broja");
    }

    /**
     * NIAS je produkcijski put prijave, pa je ovo u praksi najčešći opoziv. Dok se gledao samo
     * prefiks {@code LESSOR:}, dobivao je „Obavijest o povlačenju" — akt po službenoj dužnosti.
     */
    @Test
    void withdrawal_byNiasLessor_filesRevocationAct() {
        listener().onLifecycleChange(event(RnStatus.ACTIVE, RnStatus.WITHDRAWN,
                RnTrigger.WITHDRAWAL, "NIAS:12345678903", null));

        assertThat(capturedAkt().getVrstaPismenaNaziv())
                .isEqualTo("Obavijest o opozivu registracijskog broja");
    }

    @Test
    void withdrawal_byAuthority_filesWithdrawalAct() {
        listener().onLifecycleChange(event(RnStatus.ACTIVE, RnStatus.WITHDRAWN,
                RnTrigger.WITHDRAWAL, null, "nalaz inspekcije"));

        assertThat(capturedAkt().getVrstaPismenaNaziv())
                .isEqualTo("Obavijest o povlačenju registracijskog broja");
    }

    /**
     * Ponovljivi akti: ciklus suspenzija → reaktivacija → suspenzija daje dvije obavijesti
     * iste vrste nad istim submissionom. Razlikuje ih act_ref (log_id prijelaza) — bez toga
     * bi drugi upis pao na unique constraintu.
     */
    @Test
    void repeatedSuspension_usesDistinctActRef() {
        UUID prvi = UUID.randomUUID();
        UUID drugi = UUID.randomUUID();

        listener().onLifecycleChange(new RnLifecycleEvent(prvi, RN, RnStatus.ACTIVE,
                RnStatus.SUSPENDED, RnTrigger.INSPECTION, null, null));
        listener().onLifecycleChange(new RnLifecycleEvent(drugi, RN, RnStatus.ACTIVE,
                RnStatus.SUSPENDED, RnTrigger.INSPECTION, null, null));

        ArgumentCaptor<EgopPismenoEntity> captor = ArgumentCaptor.forClass(EgopPismenoEntity.class);
        verify(store, org.mockito.Mockito.times(2)).saveAkt(captor.capture());
        assertThat(captor.getAllValues().get(0).getActRef()).isEqualTo(prvi.toString());
        assertThat(captor.getAllValues().get(1).getActRef()).isEqualTo(drugi.toString());
    }

    /**
     * Vrsta bez šifre u eGOP šifrarniku se <b>zapisuje</b> — akt time postoji u popisu
     * dokumenata RB-a i stranka ga može preuzeti. Staje samo slanje, jer bi razrješavanje
     * vrste pismena palo i vrtjelo se do iscrpljenja pokušaja.
     */
    @Test
    void typeWithoutCodebookEntry_isRecordedButNotFiled() {
        listener("reaktivacija").onLifecycleChange(event(RnStatus.SUSPENDED, RnStatus.ACTIVE,
                RnTrigger.REACTIVATE, null, null));

        EgopPismenoEntity akt = capturedAkt();
        assertThat(akt.getPdfContent()).isNotEmpty();
        assertThat(akt.getRn()).isEqualTo(RN);
        verify(dispatcher, never()).dispatch(any());
    }

    /** Blokadna lista je po slugu, pa vrijedi za bilo koju vrstu — ne samo za reaktivaciju. */
    @Test
    void suspensionProposal_isRecordedButNotFiledWhenOnBlocklist() {
        listener("prijedlog-suspenzije").onLifecycleChange(event(RnStatus.ACTIVE,
                RnStatus.SUSPENSION_PROPOSED, RnTrigger.INSPECTION, null, null));

        assertThat(capturedAkt().getVrstaPismenaNaziv())
                .isEqualTo("Obavijest o prijedlogu suspenzije registracijskog broja");
        verify(dispatcher, never()).dispatch(any());
    }

    /** Nepoznat slug u konfiguraciji ne smije slučajno zaustaviti neku drugu vrstu. */
    @Test
    void unknownBlocklistSlug_isIgnored() {
        listener("tipfeler").onLifecycleChange(event(RnStatus.ACTIVE, RnStatus.SUSPENDED,
                RnTrigger.INSPECTION, null, null));

        EgopPismenoEntity akt = capturedAkt();
        verify(dispatcher).dispatch(akt.getId());
    }

    @Test
    void reactivation_isFiledWhenNotOnBlocklist() {
        listener().onLifecycleChange(event(RnStatus.SUSPENDED, RnStatus.ACTIVE,
                RnTrigger.REACTIVATE, null, null));

        assertThat(capturedAkt().getVrstaPismenaNaziv())
                .isEqualTo("Obavijest o reaktivaciji registracijskog broja");
    }

    /** Izdavanje RB-a urudžbira registracijski tok, s PDF-om izvedenim iz urudžbenog broja. */
    @Test
    void issuance_isHandledByRegistrationFlow() {
        listener().onLifecycleChange(event(RnStatus.IN_PROCESSING, RnStatus.ACTIVE,
                RnTrigger.ISSUE, null, null));

        verify(store, never()).saveAkt(any());
    }

    /** Bez dokumenta nema urudžbe — akt se ne zapisuje da ga retry ne bi vrtio prazan. */
    @Test
    void renderFailure_doesNotRecordAct() {
        when(documentService.render(any(), any(), any()))
                .thenThrow(new IllegalStateException("nema predloška"));

        listener().onLifecycleChange(event(RnStatus.ACTIVE, RnStatus.SUSPENDED,
                RnTrigger.INSPECTION, null, null));

        verify(store, never()).saveAkt(any());
        verify(dispatcher, never()).dispatch(any());
    }

    @Test
    void missingRn_skips() {
        when(lookup.findSubmissionId(RN)).thenReturn(Optional.empty());

        listener().onLifecycleChange(event(RnStatus.ACTIVE, RnStatus.SUSPENDED,
                RnTrigger.INSPECTION, null, null));

        verify(store, never()).saveAkt(any());
    }

    private EgopPismenoEntity capturedAkt() {
        ArgumentCaptor<EgopPismenoEntity> captor = ArgumentCaptor.forClass(EgopPismenoEntity.class);
        verify(store).saveAkt(captor.capture());
        return captor.getValue();
    }

    private static RnLifecycleEvent event(RnStatus from, RnStatus to, RnTrigger trigger,
                                          String actor, String reason) {
        return new RnLifecycleEvent(UUID.randomUUID(), RN, from, to, trigger, actor, reason);
    }

    /** Vrste pismena moraju odgovarati eGOP šifrarniku — mapiranje ide preko StrDocumentType. */
    @Test
    void actTypes_matchCodebookNames() {
        assertThat(StrDocumentType.forTransition(RnStatus.SUSPENSION_PROPOSED,
                RnTrigger.INSPECTION, false)).contains(StrDocumentType.PRIJEDLOG_SUSPENZIJE);
        assertThat(StrDocumentType.forTransition(RnStatus.SUSPENDED, RnTrigger.DEADLINE_EXCEEDED,
                false)).contains(StrDocumentType.SUSPENZIJA);
        assertThat(StrDocumentType.forTransition(RnStatus.ACTIVE, RnTrigger.REVOKE_PROPOSAL,
                false)).contains(StrDocumentType.OBUSTAVA_SUSPENZIJE);
        assertThat(StrDocumentType.forTransition(RnStatus.ACTIVE, RnTrigger.REACTIVATE, false))
                .contains(StrDocumentType.REAKTIVACIJA);
        assertThat(StrDocumentType.forTransition(RnStatus.WITHDRAWN, RnTrigger.WITHDRAWAL, true))
                .contains(StrDocumentType.OPOZIV);
        assertThat(StrDocumentType.forTransition(RnStatus.WITHDRAWN, RnTrigger.WITHDRAWAL, false))
                .contains(StrDocumentType.POVLACENJE);
        assertThat(StrDocumentType.forTransition(RnStatus.ACTIVE, RnTrigger.ISSUE, false)).isEmpty();
    }
}
