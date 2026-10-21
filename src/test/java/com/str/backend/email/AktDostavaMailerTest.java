package com.str.backend.email;

import com.str.backend.document.DocumentLabels;
import com.str.backend.domain.RnStatus;
import com.str.backend.domain.RnTrigger;
import com.str.backend.rn.RnLifecycleLookup;
import com.str.backend.rn.dto.RnDetailDto;
import com.str.backend.rn.event.RnLifecycleEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Zamjenska dostava akta e-poštom: mail je kanal dostave, pa nosi akt i klauzulu o dostavi. */
class AktDostavaMailerTest {

    private static final String RN = "HR180000123456789001";
    private static final byte[] PDF = "%PDF-akt".getBytes();

    private RnLifecycleLookup lookup;
    private EmailService emailService;
    private AktDostavaMailer mailer;

    @BeforeEach
    void setUp() {
        lookup = mock(RnLifecycleLookup.class);
        emailService = mock(EmailService.class);
        mailer = new AktDostavaMailer(lookup, new DocumentLabels(), emailService);
    }

    @Test
    void dodjela_isServedByEmailWithAct() {
        when(lookup.findDetail(RN)).thenReturn(Optional.of(detail("ana@example.com")));
        when(emailService.sendRnIssuedNotification(any())).thenReturn(true);

        assertThat(mailer.dostaviDodjelu(RN, PDF)).isEqualTo(AktDostavaMailer.Ishod.SENT);

        ArgumentCaptor<RnIssuedMail> mail = ArgumentCaptor.forClass(RnIssuedMail.class);
        verify(emailService).sendRnIssuedNotification(mail.capture());
        assertThat(mail.getValue().dostavaMailom()).isTrue();
        assertThat(mail.getValue().pdf()).isEqualTo(PDF);
        assertThat(mail.getValue().to()).isEqualTo("ana@example.com");
    }

    @Test
    void lifecycleAct_usesTemplateOfItsTransition() {
        UUID logId = UUID.randomUUID();
        when(lookup.findEvent(logId)).thenReturn(Optional.of(new RnLifecycleEvent(logId, RN,
                RnStatus.SUSPENSION_PROPOSED, RnStatus.SUSPENDED, RnTrigger.DEADLINE_EXCEEDED, null, null)));
        when(lookup.findDetail(RN)).thenReturn(Optional.of(detail("ana@example.com")));
        when(emailService.sendRnLifecycleNotification(any())).thenReturn(true);

        assertThat(mailer.dostaviAkt(logId, PDF)).isEqualTo(AktDostavaMailer.Ishod.SENT);

        ArgumentCaptor<RnLifecycleMail> mail = ArgumentCaptor.forClass(RnLifecycleMail.class);
        verify(emailService).sendRnLifecycleNotification(mail.capture());
        assertThat(mail.getValue().template()).isEqualTo(MailTemplate.SUSPENZIJA);
        assertThat(mail.getValue().dostavaMailom()).isTrue();
        assertThat(mail.getValue().pdf()).isEqualTo(PDF);
    }

    @Test
    void withoutEmail_isNoEmail_andNothingIsSent() {
        when(lookup.findDetail(RN)).thenReturn(Optional.of(detail(" ")));

        assertThat(mailer.dostaviDodjelu(RN, PDF)).isEqualTo(AktDostavaMailer.Ishod.NO_EMAIL);

        verify(emailService, never()).sendRnIssuedNotification(any());
    }

    @Test
    void smtpFailure_isFailed() {
        when(lookup.findDetail(RN)).thenReturn(Optional.of(detail("ana@example.com")));
        when(emailService.sendRnIssuedNotification(any())).thenReturn(false);

        assertThat(mailer.dostaviDodjelu(RN, PDF)).isEqualTo(AktDostavaMailer.Ishod.FAILED);
    }

    @Test
    void unknownLog_isMissing() {
        assertThat(mailer.dostaviAkt(UUID.randomUUID(), PDF)).isEqualTo(AktDostavaMailer.Ishod.MISSING);
    }

    private static RnDetailDto detail(String email) {
        return new RnDetailDto(RN, RnStatus.SUSPENDED, null, null, null, null, null, null, null,
                null, null, null, "Zagreb", "Ilica", "1", "Apartman Ana", null, null, null,
                null, "Ana", "Anić", null, email, "12345678903",
                false, null, null, null,
                null, null, null, null, null);
    }
}
