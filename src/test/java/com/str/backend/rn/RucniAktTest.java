package com.str.backend.rn;

import com.str.backend.domain.RnStatus;
import com.str.backend.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RucniAktTest {

    private static final RucniAkt PRIJEDLOG = new RucniAkt("334-06/26-01/7", "2181-26-2", "Splitsko-dalmatinska županija",
            "40781519492", "Upravni odjel", 17);

    @Test
    void provjeri_cistiIVraca() {
        assertThat(RucniAkt.provjeri(" 334-06/26-01/7 ", " 2181-26-2 ")).containsExactly("334-06/26-01/7", "2181-26-2");
    }

    @Test
    void provjeri_obaveznaPolja() {
        assertThatThrownBy(() -> RucniAkt.provjeri(" ", "2181-26-2")).isInstanceOf(BusinessException.class)
                .hasMessage("error.rn.akt.klasa.required");
        assertThatThrownBy(() -> RucniAkt.provjeri("334", null)).isInstanceOf(BusinessException.class)
                .hasMessage("error.rn.akt.urbroj.required");
    }

    @Test
    void provjeri_duljinaIFormat() {
        assertThatThrownBy(() -> RucniAkt.provjeri("x".repeat(65), "2181-26-2")).hasMessage("error.rn.akt.tooLong");
        assertThatThrownBy(() -> RucniAkt.provjeri("334", "2181-26-a")).hasMessage("error.rn.akt.urbroj.format");
    }

    /** Prijelom reda ili bidi znak razbio bi zaglavlje akta. */
    @Test
    void provjeri_bezKontrolnihZnakova() {
        assertThatThrownBy(() -> RucniAkt.provjeri("334\n06", "2181-26-2")).hasMessage("error.rn.akt.invalidChars");
        assertThatThrownBy(() -> RucniAkt.provjeri("334", "2181\u202E-26-2")).hasMessage("error.rn.akt.invalidChars");
    }

    @Test
    void sljedeci_uvecavaRedniBrojIZadrzavaOstalo() {
        RucniAkt sljedeci = PRIJEDLOG.sljedeci();

        assertThat(sljedeci.urBroj()).isEqualTo("2181-26-3");
        assertThat(sljedeci.klasa()).isEqualTo(PRIJEDLOG.klasa());
        assertThat(sljedeci.izdavateljNaziv()).isEqualTo(PRIJEDLOG.izdavateljNaziv());
        assertThat(sljedeci.izdavateljZuRb()).isEqualTo(17);
    }

    @Test
    void sljedeci_vodeceNuleIPrelazak() {
        assertThat(withUrbroj("529-06-03/01-26-09").sljedeci().urBroj()).isEqualTo("529-06-03/01-26-10");
        assertThat(withUrbroj("2181-26-9").sljedeci().urBroj()).isEqualTo("2181-26-10");
        assertThat(withUrbroj("12").sljedeci().urBroj()).isEqualTo("13");
    }

    @Test
    void izPrijedloga_samoRucniPrijedlog() {
        RegistrationNumberLogRepository logs = mock(RegistrationNumberLogRepository.class);
        when(logs.findFirstByRnAndToStatusOrderByOccurredAtDesc("RN1", "SUSPENSION_PROPOSED")).thenReturn(Optional.of(
                RegistrationNumberLogEntity.transition("RN1", "ACTIVE", "SUSPENSION_PROPOSED", "INSPECTION", "INTERNAL:1",
                        null, PRIJEDLOG)));
        when(logs.findFirstByRnAndToStatusOrderByOccurredAtDesc("RN2", "SUSPENSION_PROPOSED")).thenReturn(Optional.of(
                RegistrationNumberLogEntity.transition("RN2", "ACTIVE", "SUSPENSION_PROPOSED", "INSPECTION", "INTERNAL:1",
                        null)));

        assertThat(RucniAkt.izPrijedloga(logs, "RN1", RnStatus.SUSPENSION_PROPOSED))
                .hasValueSatisfying(r -> assertThat(r.urBroj()).isEqualTo("2181-26-3"));
        assertThat(RucniAkt.izPrijedloga(logs, "RN2", RnStatus.SUSPENSION_PROPOSED)).isEmpty();
        assertThat(RucniAkt.izPrijedloga(logs, "RN3", RnStatus.SUSPENSION_PROPOSED)).isEmpty();
    }

    private static RucniAkt withUrbroj(String urbroj) {
        return new RucniAkt("k", urbroj, "n", null, null, 1);
    }
}
