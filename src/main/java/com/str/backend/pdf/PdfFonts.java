package com.str.backend.pdf;

import com.lowagie.text.pdf.BaseFont;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;

public final class PdfFonts {

    private static final Logger log = LoggerFactory.getLogger(PdfFonts.class);

    private static final String REGULAR = "fonts/arial.ttf";
    private static final String BOLD = "fonts/arialbd.ttf";

    private PdfFonts() {}

    public static BaseFont loadArial() {
        return load(REGULAR);
    }

    /**
     * Pravi bold rez umjesto sintetskog podebljanja. Bitno na ZUP aktima, gdje su naslov
     * akta i naslovi sekcija jedini tipografski signal strukture.
     */
    public static BaseFont loadArialBold() {
        return load(BOLD);
    }

    private static BaseFont load(String resource) {
        try {
            InputStream is = PdfFonts.class.getClassLoader().getResourceAsStream(resource);
            if (is != null) {
                byte[] bytes = is.readAllBytes();
                return BaseFont.createFont(resource, BaseFont.IDENTITY_H,
                        BaseFont.EMBEDDED, true, bytes, null);
            }
        } catch (Exception e) {
            log.warn("pdf_font_load_failed resource={} — prelazak na Helvetica/Cp1250", resource, e);
        }
        // Zamjenski font nije ugrađen i pokriva samo Cp1250 — hrvatska dijakritika ovisi o
        // pregledniku. Zato pad na ovu granu mora biti vidljiv u logu, a ne tih.
        log.warn("pdf_font_fallback resource={} — dijakritika možda neće biti ispravna", resource);
        try {
            return BaseFont.createFont(BaseFont.HELVETICA, "Cp1250", BaseFont.NOT_EMBEDDED);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot initialise PDF fonts", e);
        }
    }
}
