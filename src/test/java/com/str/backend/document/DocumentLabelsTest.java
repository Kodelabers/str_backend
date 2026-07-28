package com.str.backend.document;

import com.str.backend.domain.RnStatus;
import com.str.backend.domain.RnTrigger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regresija: {@code ZupContextFactory} zove {@code labels.status(...)} pri <b>svakom</b>
 * renderu, a {@link DocumentLabels#get} baca na nedostajući ključ. Kad je uveden status
 * {@code SUSPENSION_PROPOSED} (changeset 056), natpis nije dodan — pa je render bilo kojeg
 * akta za RB u tom statusu pucao, a kvar se vidio tek u {@code AFTER_COMMIT} listeneru,
 * nakon što je status već promijenjen.
 *
 * <p>Parametrizacija nad {@code values()} znači da novi status ili okidač bez natpisa obori
 * build, umjesto da tiho obori akt strankama.
 */
class DocumentLabelsTest {

    private final DocumentLabels labels = new DocumentLabels();

    @ParameterizedTest
    @EnumSource(RnStatus.class)
    void everyStatus_hasLabel(RnStatus status) {
        assertThatCode(() -> labels.status(status)).doesNotThrowAnyException();
        assertThat(labels.status(status)).isNotBlank();
    }

    @ParameterizedTest
    @EnumSource(RnTrigger.class)
    void everyTrigger_hasLabel(RnTrigger trigger) {
        assertThatCode(() -> labels.trigger(trigger)).doesNotThrowAnyException();
        assertThat(labels.trigger(trigger)).isNotBlank();
    }

    /** {@code null} je legitiman ulaz — akt bez razloga i RB bez statusa se svejedno renderiraju. */
    @Test
    void nullValues_fallBackToPlaceholders() {
        assertThat(labels.status(null)).isEqualTo(labels.get("vrijednost.nepoznata"));
        assertThat(labels.trigger(null)).isEqualTo(labels.get("razlog.nijeNaveden"));
    }

    @Test
    void missingKey_failsLoudly() {
        assertThatThrownBy(() -> labels.get("status.NE_POSTOJI"))
                .isInstanceOf(DocumentTemplateException.class)
                .hasMessageContaining("status.NE_POSTOJI");
    }

    /** Datoteka se čita UTF-8 Readerom; s InputStreamom bi dijakritika došla iskrivljena. */
    @Test
    void labelsAreReadAsUtf8() {
        assertThat(labels.status(RnStatus.WITHDRAWN)).isEqualTo("povučen");
    }
}
