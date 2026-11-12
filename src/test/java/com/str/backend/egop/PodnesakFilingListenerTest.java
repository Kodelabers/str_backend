package com.str.backend.egop;

import com.str.backend.document.StrDocumentType;
import com.str.backend.domain.RnStatus;
import com.str.backend.objection.SuspensionObjectionSubmittedEvent;
import com.str.backend.rn.RnLifecycleLookup;
import com.str.backend.rn.RucniAkt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Bug s testiranja MINTS-a: očitovanje i prigovor iznajmljivača postaju ulazno pismeno u eGOP-u, osim u
 * postupku koji vodi županijski službenik (T16).
 */
class PodnesakFilingListenerTest {

    private static final String RN = "HR120001000000000123";

    private RnLifecycleLookup lookup;
    private EgopFilingStore store;
    private EgopAktDispatcher dispatcher;
    private PodnesakFilingListener listener;
    private UUID submissionId;

    @BeforeEach
    void setUp() {
        lookup = mock(RnLifecycleLookup.class);
        store = mock(EgopFilingStore.class);
        dispatcher = mock(EgopAktDispatcher.class);
        listener = new PodnesakFilingListener(lookup, store, dispatcher, new EgopAktiBezSifre(Set.of()));
        submissionId = UUID.randomUUID();
        when(lookup.findSubmissionId(RN)).thenReturn(Optional.of(submissionId));
        when(lookup.findRucniAkt(any())).thenReturn(Optional.empty());
        when(store.saveAkt(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static SuspensionObjectionSubmittedEvent event(RnStatus status) {
        return new SuspensionObjectionSubmittedEvent(UUID.randomUUID(), RN, status, UUID.randomUUID());
    }

    /** Vrsta pismena po statusu broja; ulazno, bez dokumenta na pismenu, act_ref jedinstven po podnesku. */
    @ParameterizedTest
    @CsvSource({
            "SUSPENSION_PROPOSED, Očitovanje na prijedlog suspenzije registracijskog broja",
            "SUSPENDED, Prigovor na suspenziju registracijskog broja",
            "WITHDRAWAL_PROPOSED, Očitovanje na prijedlog povlačenja registracijskog broja",
            "WITHDRAWN, Prigovor na povlačenje registracijskog broja"})
    void recordsIncomingPismeno_andDispatchesIt(RnStatus status, String vrsta) {
        SuspensionObjectionSubmittedEvent event = event(status);

        listener.onSubmitted(event);

        ArgumentCaptor<EgopPismenoEntity> pismeno = ArgumentCaptor.forClass(EgopPismenoEntity.class);
        verify(store).saveAkt(pismeno.capture());
        EgopPismenoEntity p = pismeno.getValue();
        assertThat(p.getVrstaPismenaNaziv()).isEqualTo(vrsta);
        assertThat(p.getSmjer()).isEqualTo(EgopPismenoEntity.Smjer.ULAZNO);
        assertThat(p.getSubmissionId()).isEqualTo(submissionId);
        assertThat(p.getRn()).isEqualTo(RN);
        assertThat(p.getActRef()).isEqualTo("PRIGOVOR:" + event.objectionId()).hasSizeLessThanOrEqualTo(64);
        assertThat(p.objectionId()).contains(event.objectionId());
        assertThat(p.getPdfContent()).isNull();
        assertThat(p.isRucno()).isFalse();
        verify(dispatcher).dispatch(p.getId());
    }

    /** Dva podneska na isti prijelaz (npr. prvi zatvoren, pa novi) daju dva pismena — act_ref je po podnesku. */
    @Test
    void twoObjectionsOnSameTransition_haveDistinctActRefs() {
        UUID logId = UUID.randomUUID();
        listener.onSubmitted(new SuspensionObjectionSubmittedEvent(UUID.randomUUID(), RN, RnStatus.SUSPENDED, logId));
        listener.onSubmitted(new SuspensionObjectionSubmittedEvent(UUID.randomUUID(), RN, RnStatus.SUSPENDED, logId));

        ArgumentCaptor<EgopPismenoEntity> pismeno = ArgumentCaptor.forClass(EgopPismenoEntity.class);
        verify(store, org.mockito.Mockito.times(2)).saveAkt(pismeno.capture());
        assertThat(pismeno.getAllValues()).extracting(EgopPismenoEntity::getActRef).doesNotHaveDuplicates();
    }

    /**
     * Postupak vodi županijski službenik (ručna KLASA i URBROJ na zapisu prijelaza na koji podnesak odgovara):
     * podnesak ostaje samo u STR-u — nema pismena ni slanja.
     */
    @Test
    void countyProcedure_staysInStrOnly() {
        SuspensionObjectionSubmittedEvent event = event(RnStatus.SUSPENSION_PROPOSED);
        when(lookup.findRucniAkt(event.rnLogId())).thenReturn(Optional.of(
                new RucniAkt("334-06/26-01/5", "2170-26-1", "Primorsko-goranska županija", null, null, 8)));

        listener.onSubmitted(event);

        verify(store, never()).saveAkt(any());
        verify(dispatcher, never()).dispatch(any());
    }

    /** Bez zapisa prijelaza ne zna se tko vodi postupak ni iza kojeg akta podnesak ide — ne šalje se. */
    @Test
    void missingTransitionLog_isNotRecorded() {
        listener.onSubmitted(new SuspensionObjectionSubmittedEvent(UUID.randomUUID(), RN, RnStatus.SUSPENDED, null));

        verify(store, never()).saveAkt(any());
    }

    /** Na ACTIVE podneska nema (predaja ga odbija); listener ni tada ne izmišlja vrstu. */
    @Test
    void activeRn_hasNoPismeno() {
        listener.onSubmitted(event(RnStatus.ACTIVE));

        verify(store, never()).saveAkt(any());
        verify(dispatcher, never()).dispatch(any());
    }

    /** Vrsta na popisu bez šifre: zapisuje se, ali se ne šalje (kao i akti). */
    @Test
    void typeWithoutCode_isRecordedButNotSent() {
        listener = new PodnesakFilingListener(lookup, store, dispatcher,
                new EgopAktiBezSifre(Set.of(StrDocumentType.PRIGOVOR_POVLACENJA.slug())));

        listener.onSubmitted(event(RnStatus.WITHDRAWN));

        verify(store).saveAkt(any());
        verify(dispatcher, never()).dispatch(any());
    }

    /** Pad zapisa ne smije izaći iz AFTER_COMMIT listenera — iznajmljivač bi dobio grešku za zaprimljen podnesak. */
    @Test
    void recordingFailure_isContained() {
        when(store.saveAkt(any())).thenThrow(new IllegalStateException("baza"));

        assertThatCode(() -> listener.onSubmitted(event(RnStatus.SUSPENDED))).doesNotThrowAnyException();
        verify(dispatcher, never()).dispatch(any());
    }
}
