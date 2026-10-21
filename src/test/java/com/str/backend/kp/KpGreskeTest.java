package com.str.backend.kp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KpGreskeTest {

    @ParameterizedTest
    @ValueSource(strings = {"K009", "Z012", "k009"})
    void duplicateMessageId_isDuplicate(String sifra) {
        assertEquals(KpGreske.Ishod.DUPLIKAT, KpGreske.classify(sifra));
    }

    @ParameterizedTest
    @ValueSource(strings = {"K006", "Z005"})
    void noActivePretinac(String sifra) {
        assertEquals(KpGreske.Ishod.NEMA_PRETINCA, KpGreske.classify(sifra));
    }

    @ParameterizedTest
    @ValueSource(strings = {"M008", "M009", "M010", "K011", "K999", "Z003", "TRANSPORT", "X123", ""})
    void serviceDownSystemErrorAndUnknown_areTransient(String sifra) {
        assertEquals(KpGreske.Ishod.PROLAZNA, KpGreske.classify(sifra));
    }

    @ParameterizedTest
    @ValueSource(strings = {"M001", "M002", "M004", "M011", "K001", "K003", "K005", "K013", "K015", "K021",
            "K026", "K029", "Z002", "Z007", "Z009", "Z016", "Z019", "Z023"})
    void configurationRegistrationOrData_isPermanent(String sifra) {
        assertEquals(KpGreske.Ishod.TRAJNA, KpGreske.classify(sifra));
    }

    @Test
    void permanentWins_overOtherCodes() {
        assertEquals(KpGreske.Ishod.TRAJNA, KpGreske.classify(List.of("K009", "K013")));
        assertEquals(KpGreske.Ishod.NEMA_PRETINCA, KpGreske.classify(List.of("M010", "K006")));
        assertEquals(KpGreske.Ishod.DUPLIKAT, KpGreske.classify(List.of("M010", "Z012")));
        assertEquals(KpGreske.Ishod.PROLAZNA, KpGreske.classify(List.of()));
    }
}
