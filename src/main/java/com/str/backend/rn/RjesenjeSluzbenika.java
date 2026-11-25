package com.str.backend.rn;

/**
 * Rješenje kojim službenik ne uvažava prigovor na suspenziju ili povlačenje (UAT 25.11.2026). Službenik ga
 * sastavlja izvan sustava i prilaže uz obavijest o neprihvaćanju prigovora — obavezan prilog.
 *
 * @param prilogId prilog s dokumentom Rješenja (id iz {@code POST /api/rn/attachments})
 * @param klasa    KLASA Rješenja u županijskom postupku (provjerena, {@link RucniAkt#provjeri}); u postupku
 *                 MINTS-a {@code null} — brojevi su na samom dokumentu
 * @param urBroj   URBROJ Rješenja; kao {@code klasa}
 */
public record RjesenjeSluzbenika(Long prilogId, String klasa, String urBroj) {
}
