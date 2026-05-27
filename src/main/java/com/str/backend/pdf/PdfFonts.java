package com.str.backend.pdf;

import com.lowagie.text.pdf.BaseFont;

import java.io.InputStream;

public final class PdfFonts {

    private PdfFonts() {}

    public static BaseFont loadArial() {
        try {
            InputStream is = PdfFonts.class.getClassLoader().getResourceAsStream("fonts/arial.ttf");
            if (is != null) {
                byte[] bytes = is.readAllBytes();
                return BaseFont.createFont("fonts/arial.ttf", BaseFont.IDENTITY_H,
                        BaseFont.EMBEDDED, true, bytes, null);
            }
        } catch (Exception ignored) {}
        try {
            return BaseFont.createFont(BaseFont.HELVETICA, "Cp1250", BaseFont.NOT_EMBEDDED);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot initialise PDF fonts", e);
        }
    }
}
