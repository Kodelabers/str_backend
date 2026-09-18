package com.str.backend.document;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.LuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.lowagie.text.Image;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * QR kod u bloku e-pečata radi se ručno, iz {@code BitMatrix} u 1-bitnu sliku — pa nije dovoljno
 * provjeriti da slika postoji. Ovi testovi ga <b>dekodiraju natrag</b>: sprječavaju da se
 * zamjenom bitova, redoslijedom ili skaliranjem dobije kod koji nijedan čitač ne razumije.
 */
class EpecatBlokTest {

    private static final String URL =
            "https://str-test-eturizam.gov.hr/provjera?zapis=6f0f6b0e-0000-4000-8000-000000000001&kb=12345678";

    @Test
    void qr_isDecodableAndCarriesVerificationUrl() throws Exception {
        assertThat(dekodiraj(EpecatBlok.qrModuli(URL))).isEqualTo(URL);
    }

    /** Dijakritika u nazivu tijela ili URL-u ne smije razbiti kod — zato UTF-8 hint. */
    @Test
    void qr_handlesDiacritics() throws Exception {
        String sadrzaj = "https://provjera.example.hr/?tijelo=MINISTARSTVO%20TURIZMA&note=čćšž";

        assertThat(dekodiraj(EpecatBlok.qrModuli(sadrzaj))).isEqualTo(sadrzaj);
    }

    /** Slika u PDF-u nosi jedan piksel po modulu i skalira se na fiksnu veličinu ćelije. */
    @Test
    void qr_imageIsOneRowPerModule() throws Exception {
        Image slika = EpecatBlok.qr(URL);

        assertThat((int) slika.getWidth()).isEqualTo(EpecatBlok.qrModuli(URL).stranica());
        assertThat(slika.getScaledWidth()).isEqualTo(slika.getScaledHeight());
    }

    /**
     * zxing na praznom sadržaju baca {@code IllegalArgumentException}, koji nije
     * {@code WriterException} — bez izričitog hvatanja izlazio bi kao 500 bez ijedne domenske
     * poruke. Blok ga zato ni ne zove s praznim sadržajem (vidi {@code izgradi}), ali zaštita
     * ostaje.
     */
    @Test
    void qr_blankContent_failsWithDomainException() {
        assertThatThrownBy(() -> EpecatBlok.qr(""))
                .isInstanceOf(DocumentTemplateException.class)
                .hasMessageContaining("QR kod e-pečata");
    }

    @Test
    void bodyName_breaksAfterFirstWord() {
        assertThat(EpecatBlok.uDvaRetka("MINISTARSTVO TURIZMA I SPORTA"))
                .isEqualTo("MINISTARSTVO\nTURIZMA I SPORTA");
        assertThat(EpecatBlok.uDvaRetka("MINISTARSTVO")).isEqualTo("MINISTARSTVO");
        assertThat(EpecatBlok.uDvaRetka("")).isEmpty();
    }

    /** Čita spakirane 1-bitne redove natrag u module i pušta zxing čitač na njih. */
    private static String dekodiraj(EpecatBlok.Moduli qr) throws Exception {
        int n = qr.stranica();
        byte[] podaci = qr.bitovi();
        int bajtovaPoRedu = (n + 7) / 8;
        byte[] svjetlina = new byte[n * n];
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                boolean bijelo = (podaci[y * bajtovaPoRedu + x / 8] & (0x80 >> (x % 8))) != 0;
                svjetlina[y * n + x] = (byte) (bijelo ? 0xFF : 0x00);
            }
        }
        LuminanceSource source = new LuminanceSource(n, n) {
            @Override
            public byte[] getRow(int y, byte[] row) {
                byte[] out = row != null && row.length >= n ? row : new byte[n];
                System.arraycopy(svjetlina, y * n, out, 0, n);
                return out;
            }

            @Override
            public byte[] getMatrix() {
                return svjetlina;
            }
        };
        return new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(source))).getText();
    }
}
