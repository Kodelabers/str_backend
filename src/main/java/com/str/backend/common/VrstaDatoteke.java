package com.str.backend.common;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Vrsta učitane datoteke, određena iz <b>sadržaja</b> (magic bytes), ne iz {@code Content-Type}
 * headera — header postavlja klijent i može lagati. Jedino mjesto s potpisima formata; rješenje o
 * kategorizaciji, prigovor i prilozi uz pismena biraju samo koje vrste dopuštaju.
 *
 * <p>Potpisi Worda nisu jedinstveni (DOCX je ZIP arhiva, DOC je OLE2 kao i stari Excel), pa se uz
 * potpis traži i odgovarajuća ekstenzija — inače bi prošla bilo koja arhiva ili Office datoteka.
 * PDF i slike se prepoznaju po samom potpisu.
 */
public enum VrstaDatoteke {

    PDF("application/pdf", "pdf", new byte[]{0x25, 0x50, 0x44, 0x46}, null),                     // %PDF
    JPEG("image/jpeg", "jpg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}, null),
    PNG("image/png", "png", new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}, null),
    DOC("application/msword", "doc", new byte[]{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
            (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1}, ".doc"),                                       // OLE2
    DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx",
            new byte[]{0x50, 0x4B, 0x03, 0x04}, ".docx");                                                // ZIP

    private final String contentType;
    private final String ekstenzija;
    private final byte[] potpis;
    /** Ekstenzija koju naziv mora imati uz potpis; {@code null} kad je potpis dovoljan. */
    private final String obaveznaEkstenzija;

    VrstaDatoteke(String contentType, String ekstenzija, byte[] potpis, String obaveznaEkstenzija) {
        this.contentType = contentType;
        this.ekstenzija = ekstenzija;
        this.potpis = potpis;
        this.obaveznaEkstenzija = obaveznaEkstenzija;
    }

    public String contentType() {
        return contentType;
    }

    /** Ekstenzija bez točke, npr. za eGOP {@code extension}. */
    public String ekstenzija() {
        return ekstenzija;
    }

    /**
     * Vrsta sadržaja među {@code dopustene}; prazno kad sadržaj nije nijedna od njih.
     *
     * @param nazivDatoteke naziv iz uploada (za DOC/DOCX ekstenzija mora odgovarati potpisu)
     */
    public static Optional<VrstaDatoteke> odredi(byte[] sadrzaj, String nazivDatoteke, Set<VrstaDatoteke> dopustene) {
        if (sadrzaj == null) {
            return Optional.empty();
        }
        String naziv = nazivDatoteke == null ? "" : nazivDatoteke.toLowerCase(Locale.ROOT);
        for (VrstaDatoteke vrsta : values()) {
            if (dopustene.contains(vrsta) && vrsta.odgovara(sadrzaj, naziv)) {
                return Optional.of(vrsta);
            }
        }
        return Optional.empty();
    }

    /** Vrsta po spremljenom MIME tipu; prazno za nepoznati. */
    public static Optional<VrstaDatoteke> poContentType(String contentType) {
        for (VrstaDatoteke vrsta : values()) {
            if (vrsta.contentType.equals(contentType)) {
                return Optional.of(vrsta);
            }
        }
        return Optional.empty();
    }

    private boolean odgovara(byte[] sadrzaj, String nazivMalimSlovima) {
        boolean potpisOk = sadrzaj.length >= potpis.length
                && Arrays.equals(sadrzaj, 0, potpis.length, potpis, 0, potpis.length);
        return potpisOk && (obaveznaEkstenzija == null || nazivMalimSlovima.endsWith(obaveznaEkstenzija));
    }
}
