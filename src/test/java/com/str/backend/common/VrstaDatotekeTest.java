package com.str.backend.common;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class VrstaDatotekeTest {

    private static final Set<VrstaDatoteke> SVE = EnumSet.allOf(VrstaDatoteke.class);

    static final byte[] PDF = "%PDF-1.7".getBytes();
    static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0};
    static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0};
    static final byte[] OLE2 = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1, 0};
    static final byte[] ZIP = {0x50, 0x4B, 0x03, 0x04, 0};

    @Test
    void detectsEveryTypeBySignature() {
        assertThat(VrstaDatoteke.odredi(PDF, "a.bin", SVE)).contains(VrstaDatoteke.PDF);
        assertThat(VrstaDatoteke.odredi(JPEG, "foto", SVE)).contains(VrstaDatoteke.JPEG);
        assertThat(VrstaDatoteke.odredi(PNG, "slika.png", SVE)).contains(VrstaDatoteke.PNG);
        assertThat(VrstaDatoteke.odredi(OLE2, "Dopis.DOC", SVE)).contains(VrstaDatoteke.DOC);
        assertThat(VrstaDatoteke.odredi(ZIP, "dopis.docx", SVE)).contains(VrstaDatoteke.DOCX);
    }

    /** Potpis Worda nije jedinstven — ZIP ili OLE2 bez Word ekstenzije nije Word. */
    @Test
    void officeSignatureWithoutMatchingExtension_isRejected() {
        assertThat(VrstaDatoteke.odredi(ZIP, "arhiva.zip", SVE)).isEmpty();
        assertThat(VrstaDatoteke.odredi(OLE2, "tablica.xls", SVE)).isEmpty();
        assertThat(VrstaDatoteke.odredi(ZIP, "dopis.doc", SVE)).isEmpty();
        assertThat(VrstaDatoteke.odredi(OLE2, null, SVE)).isEmpty();
    }

    /** Klijentov naziv ne određuje vrstu: tekst preimenovan u .pdf nije PDF. */
    @Test
    void contentDecides_notExtension() {
        assertThat(VrstaDatoteke.odredi("hello".getBytes(), "lazni.pdf", SVE)).isEmpty();
        assertThat(VrstaDatoteke.odredi(new byte[]{0x25}, "kratki.pdf", SVE)).isEmpty();
        assertThat(VrstaDatoteke.odredi(new byte[0], "prazan.pdf", SVE)).isEmpty();
        assertThat(VrstaDatoteke.odredi(null, "nista.pdf", SVE)).isEmpty();
    }

    @Test
    void onlyAllowedTypesAreAccepted() {
        Set<VrstaDatoteke> prigovor = EnumSet.of(VrstaDatoteke.PDF, VrstaDatoteke.DOC, VrstaDatoteke.DOCX);
        assertThat(VrstaDatoteke.odredi(JPEG, "foto.jpg", prigovor)).isEmpty();
        assertThat(VrstaDatoteke.odredi(PDF, "a.pdf", prigovor)).contains(VrstaDatoteke.PDF);
    }

    @Test
    void contentTypeAndExtensionRoundTrip() {
        for (VrstaDatoteke vrsta : VrstaDatoteke.values()) {
            assertThat(VrstaDatoteke.poContentType(vrsta.contentType())).contains(vrsta);
        }
        assertThat(VrstaDatoteke.DOCX.ekstenzija()).isEqualTo("docx");
        assertThat(VrstaDatoteke.JPEG.ekstenzija()).isEqualTo("jpg");
        assertThat(VrstaDatoteke.poContentType("text/plain")).isEqualTo(Optional.empty());
    }
}
