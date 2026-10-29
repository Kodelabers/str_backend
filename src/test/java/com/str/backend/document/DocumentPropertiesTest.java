package com.str.backend.document;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentPropertiesTest {

    private static DocumentProperties.Tijelo tijelo(String naziv) {
        return new DocumentProperties.Tijelo(naziv, null, null, null, null, null);
    }

    @Test
    void nazivTijela_uvijekPocinjeVelikimSlovom() {
        assertThat(tijelo("ministarstvo turizma i sporta").naziv())
                .isEqualTo("Ministarstvo turizma i sporta");
        assertThat(tijelo("  ministarstvo turizma i sporta ").naziv())
                .isEqualTo("Ministarstvo turizma i sporta");
        assertThat(tijelo("čelnik tijela").naziv()).isEqualTo("Čelnik tijela");
    }

    @Test
    void nazivTijela_ostatakOstajeKakoJeUpisan() {
        assertThat(tijelo("Ministarstvo turizma i sporta").naziv())
                .isEqualTo("Ministarstvo turizma i sporta");
        assertThat(tijelo("MINISTARSTVO TURIZMA I SPORTA").naziv())
                .isEqualTo("MINISTARSTVO TURIZMA I SPORTA");
    }

    @Test
    void nazivTijela_prazanOstajePrazan() {
        assertThat(tijelo(null).naziv()).isNull();
        assertThat(tijelo("  ").naziv()).isEqualTo("  ");
        assertThat(new DocumentProperties(null, null, null, null, false, false).tijelo().naziv()).isNull();
    }
}
