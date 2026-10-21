package com.str.backend.kp;

import com.str.backend.document.StrDocumentType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;

class KpPropertiesTest {

    @Test
    void completeConfiguration_passes() {
        assertThatCode(() -> KpTestProperties.complete().requireComplete()).doesNotThrowAnyException();
    }

    /** Prazni env defaulti daju {@code null} — nije zadano, a ne greška vezanja. */
    @Test
    void emptyEverything_listsAllMissingValuesAtOnce() {
        KpProperties empty = new KpProperties(true, "", null, null, "", "", null, null, null, "", "", "", "",
                "", "", null, null, null, null, null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(empty::requireComplete)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("url")
                .hasMessageContaining("keystore-path")
                .hasMessageContaining("sender-id")
                .hasMessageContaining("id-posiljatelja-fo")
                .hasMessageContaining("id-posiljatelja-ps")
                .hasMessageContaining("izvor-registar-oib")
                .hasMessageContaining("data-encoding")
                .hasMessageContaining("content-mime-type")
                .hasMessageContaining("razina-sigurnosti")
                .hasMessageContaining("pravni-ucinak")
                .hasMessageContaining("tip-poruke-fo.dodjela")
                .hasMessageContaining("tip-poruke-ps.obustava-suspenzije");
    }

    @Test
    void forbiddenCombination_isRejected_Z019() {
        // suglasnost bez potvrde, a s e-potpisom potvrde: T/F/T
        KpProperties p = KpTestProperties.complete("EMBEDDED", true, true, false, true, 8, false, false, 11);

        assertThatThrownBy(p::requireComplete).hasMessageContaining("T/F/T").hasMessageContaining("Z019");
    }

    @Test
    void confirmationWithoutDeadline_isRejected_Z017() {
        KpProperties p = KpTestProperties.complete("EMBEDDED", true, false, true, false, null, false, false, 11);

        assertThatThrownBy(p::requireComplete).hasMessageContaining("rok-dani");
    }

    @Test
    void noConfirmation_requiresZeroDeadline_Z023() {
        KpProperties p = KpTestProperties.complete("EMBEDDED", true, false, false, false, 8, false, false, 11);

        assertThatThrownBy(p::requireComplete).hasMessageContaining("Z023");
        assertThatCode(() -> KpTestProperties.complete("EMBEDDED", true, false, false, false, 0, false, false, 11)
                .requireComplete()).doesNotThrowAnyException();
    }

    @Test
    void retentionYears_mustBeOneToNinetyNine_whenNotPermanent() {
        assertThatThrownBy(KpTestProperties.complete("EMBEDDED", true, false, true, false, 8, false, false, 100)
                ::requireComplete).hasMessageContaining("godine-cuvanja");
        assertThatCode(() -> KpTestProperties.complete("EMBEDDED", true, false, true, false, 8, false, true, null)
                .requireComplete()).doesNotThrowAnyException();
    }

    @Test
    void withoutLegalEffect_legalEffectParametersAreNotRequired() {
        assertThatCode(() -> KpTestProperties.complete("BASE64", false, null, null, null, null, null, null, null)
                .requireComplete()).doesNotThrowAnyException();
    }

    @Test
    void unknownEncoding_isRejected() {
        assertThatThrownBy(KpTestProperties.complete("MTOM", true, false, true, false, 8, false, false, 11)
                ::requireComplete).hasMessageContaining("data-encoding");
    }

    @Test
    void missingMessageTypeForOneAct_isRejected() {
        KpProperties base = KpTestProperties.complete();
        Map<String, Integer> fo = new HashMap<>(base.tipPorukeFo());
        fo.remove(StrDocumentType.REAKTIVACIJA.slug());
        KpProperties p = new KpProperties(true, base.url(), null, null, base.keystorePath(), base.keystorePassword(),
                null, null, null, base.senderId(), base.idPosiljateljaFo(), base.idPosiljateljaPs(),
                base.izvorRegistarOib(), base.dataEncoding(), base.contentMimeType(), base.razinaSigurnosti(),
                base.pravniUcinak(), base.potrebnaSuglasnost(), base.potrebnaPotvrda(), base.potrebanDsig(),
                base.rokDani(), base.neradniDanURok(), base.trajnoCuvanje(), base.godineCuvanja(), fo,
                base.tipPorukePs(), Map.of(), Duration.ofDays(7));

        assertThatThrownBy(p::requireComplete).hasMessageContaining("tip-poruke-fo.reaktivacija");
    }

    @Test
    void nullMapValuesFromEmptyEnv_areDropped() {
        Map<String, Integer> fo = new HashMap<>();
        fo.put("dodjela", null);
        fo.put("opoziv", 5);
        KpProperties p = new KpProperties(false, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, fo, null, null, null);

        assertThat(p.tipPorukeFo()).containsOnlyKeys("opoziv");
    }

    @Test
    void title_defaultsToDocumentTypeName_andCanBeOverridden() {
        KpProperties base = KpTestProperties.complete();
        assertEquals("Obavijest o dodjeli registracijskog broja", base.naslov(StrDocumentType.DODJELA));

        KpProperties p = new KpProperties(true, base.url(), null, null, base.keystorePath(), base.keystorePassword(),
                null, null, null, base.senderId(), base.idPosiljateljaFo(), base.idPosiljateljaPs(),
                base.izvorRegistarOib(), base.dataEncoding(), base.contentMimeType(), base.razinaSigurnosti(),
                base.pravniUcinak(), base.potrebnaSuglasnost(), base.potrebnaPotvrda(), base.potrebanDsig(),
                base.rokDani(), base.neradniDanURok(), base.trajnoCuvanje(), base.godineCuvanja(),
                base.tipPorukeFo(), base.tipPorukePs(), Map.of("dodjela", "Registracijski broj dodijeljen"), null);
        assertEquals("Registracijski broj dodijeljen", p.naslov(StrDocumentType.DODJELA));
        assertEquals(Duration.ofDays(7), p.maxAktAge());
    }
}
