package com.str.backend.egop;

import com.str.backend.document.StrDocumentType;
import com.str.backend.eseal.PdfSealer;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EgopPecatiranjeAktaTest {

    @Test
    void pecatiraSe_samoUzUkljucenEseal_iVrstuKojaSeUrudzbira() {
        EgopPecatiranjeAkta ukljucen = pecatiranje(true);

        assertThat(ukljucen.bitCePecatiran(StrDocumentType.POVLACENJE)).isTrue();
        // Vrsta bez eGOP šifre ne ide u eGOP, pa ni na pečat (sealOnce je samo u eGOP toku).
        assertThat(ukljucen.bitCePecatiran(StrDocumentType.SUSPENZIJA)).isFalse();
        assertThat(pecatiranje(false).bitCePecatiran(StrDocumentType.POVLACENJE)).isFalse();
    }

    private static EgopPecatiranjeAkta pecatiranje(boolean esealUkljucen) {
        PdfSealer sealer = mock(PdfSealer.class);
        when(sealer.isEnabled()).thenReturn(esealUkljucen);
        return new EgopPecatiranjeAkta(sealer, new EgopAktiBezSifre(Set.of(StrDocumentType.SUSPENZIJA.slug())));
    }
}
