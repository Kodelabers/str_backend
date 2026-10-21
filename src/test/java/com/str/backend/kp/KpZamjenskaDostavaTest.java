package com.str.backend.kp;

import com.str.backend.egop.EgopFilingStore;
import com.str.backend.egop.EgopPismenoEntity;
import com.str.backend.email.AktDostavaMailer;
import com.str.backend.email.MailRetryEntity;
import com.str.backend.email.MailRetryStore;
import com.str.backend.rn.RnEntity;
import com.str.backend.rn.RnRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KpZamjenskaDostavaTest {

    private static final String RN = "HR012345678901234567";
    private static final byte[] PDF = "%PDF-akt".getBytes();
    private static final KpPrimatelj FO = new KpPrimatelj(KpPrimatelj.Tip.FO, "12345678903", null);

    private KpOtpremaStore store;
    private EgopFilingStore filingStore;
    private RnRepository rnRepository;
    private AktDostavaMailer mailer;
    private MailRetryStore retryStore;
    private KpZamjenskaDostava dostava;

    @BeforeEach
    void setUp() {
        store = mock(KpOtpremaStore.class);
        filingStore = mock(EgopFilingStore.class);
        rnRepository = mock(RnRepository.class);
        mailer = mock(AktDostavaMailer.class);
        retryStore = mock(MailRetryStore.class);
        dostava = new KpZamjenskaDostava(store, filingStore, rnRepository, mailer, retryStore);
    }

    @Test
    void lifecycleAct_isMailedWithItsTransition() {
        UUID logId = UUID.randomUUID();
        EgopPismenoEntity akt = EgopPismenoEntity.forAct(UUID.randomUUID(), RN, logId.toString(),
                "Obavijest o suspenziji registracijskog broja", EgopPismenoEntity.Smjer.IZLAZNO, PDF);
        KpOtpremaEntity otprema = nemaPretinca(akt);
        when(mailer.dostaviAkt(logId, PDF)).thenReturn(AktDostavaMailer.Ishod.SENT);

        dostava.dostavi(otprema.getId());

        verify(mailer).dostaviAkt(logId, PDF);
        verify(retryStore).markSent(MailRetryEntity.Kind.KP_ZAMJENSKA, otprema.getId());
    }

    @Test
    void registrationAct_isMailedAsObavijestODodjeli() {
        UUID submissionId = UUID.randomUUID();
        EgopPismenoEntity pismeno = EgopPismenoEntity.create(submissionId,
                "Obavijest o dodjeli registracijskog broja", EgopPismenoEntity.Smjer.IZLAZNO,
                1002, "529-06/26-2", "102", false);
        pismeno.keepPdf(PDF);
        KpOtpremaEntity otprema = nemaPretinca(pismeno);
        RnEntity rn = mock(RnEntity.class);
        when(rn.getRn()).thenReturn(RN);
        when(rnRepository.findBySubmissionId(submissionId)).thenReturn(List.of(rn));
        when(mailer.dostaviDodjelu(RN, PDF)).thenReturn(AktDostavaMailer.Ishod.SENT);

        dostava.dostavi(otprema.getId());

        verify(mailer).dostaviDodjelu(RN, PDF);
    }

    @Test
    void smtpFailure_isQueuedForRetry() {
        EgopPismenoEntity akt = EgopPismenoEntity.forAct(UUID.randomUUID(), RN, UUID.randomUUID().toString(),
                "Obavijest o suspenziji registracijskog broja", EgopPismenoEntity.Smjer.IZLAZNO, PDF);
        KpOtpremaEntity otprema = nemaPretinca(akt);
        when(mailer.dostaviAkt(any(), any())).thenReturn(AktDostavaMailer.Ishod.FAILED);

        dostava.dostavi(otprema.getId());

        verify(retryStore).recordFailure(MailRetryEntity.Kind.KP_ZAMJENSKA, otprema.getId(), "smtp_failed");
    }

    @Test
    void noEmail_isAbandoned() {
        EgopPismenoEntity akt = EgopPismenoEntity.forAct(UUID.randomUUID(), RN, UUID.randomUUID().toString(),
                "Obavijest o suspenziji registracijskog broja", EgopPismenoEntity.Smjer.IZLAZNO, PDF);
        KpOtpremaEntity otprema = nemaPretinca(akt);
        when(mailer.dostaviAkt(any(), any())).thenReturn(AktDostavaMailer.Ishod.NO_EMAIL);

        dostava.dostavi(otprema.getId());

        verify(retryStore).abandon(MailRetryEntity.Kind.KP_ZAMJENSKA, otprema.getId(), "no_email");
        verify(retryStore, never()).recordFailure(any(), any(), anyString());
    }

    /** Samo otprema koja je stvarno završila bez pretinca — inače bi akt otišao dvama kanalima. */
    @Test
    void otpremaNotNemaPretinca_isNotMailed() {
        EgopPismenoEntity akt = EgopPismenoEntity.forAct(UUID.randomUUID(), RN, UUID.randomUUID().toString(),
                "Obavijest o suspenziji registracijskog broja", EgopPismenoEntity.Smjer.IZLAZNO, PDF);
        KpOtpremaEntity otprema = KpOtpremaEntity.pending(akt.getId(), FO, Instant.now());
        otprema.markSent(Instant.now(), 9001, true, null);
        when(store.find(otprema.getId())).thenReturn(Optional.of(otprema));
        when(filingStore.findAkt(akt.getId())).thenReturn(Optional.of(akt));

        dostava.dostavi(otprema.getId());

        verify(mailer, never()).dostaviAkt(any(), any());
        verify(retryStore).abandon(eq(MailRetryEntity.Kind.KP_ZAMJENSKA), eq(otprema.getId()), anyString());
    }

    private KpOtpremaEntity nemaPretinca(EgopPismenoEntity pismeno) {
        KpOtpremaEntity otprema = KpOtpremaEntity.pending(pismeno.getId(), FO, Instant.now());
        otprema.markNemaPretinca("K006", "nema KP", null);
        when(store.find(otprema.getId())).thenReturn(Optional.of(otprema));
        when(filingStore.findAkt(pismeno.getId())).thenReturn(Optional.of(pismeno));
        return otprema;
    }
}
