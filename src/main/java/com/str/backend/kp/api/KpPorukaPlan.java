package com.str.backend.kp.api;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Raspodjela akta i priloga na poruke KP-a. Čista funkcija, bez pristupa bazi.
 *
 * <p>KP prima samo PDF privitke i najviše {@code maxVelicina} po poruci (tekst i privici zajedno, privici u
 * base64). Akt ide u prvu poruku, a PDF prilozi redom u istu ili sljedeće. Prilog koji nije PDF, ili je
 * sam veći od jedne poruke, ne ide u pretinac; prva poruka to navodi.
 */
final class KpPorukaPlan {

    /** JSON omotnica poruke (ključevi, OIB, klasa, urbroj…). */
    static final int OMOTNICA = 2_000;

    /** Ključevi i naziv jednog privitka u JSON-u. */
    static final int PO_PRIVITKU = 400;

    static final String NAPOMENA = "Sljedeći prilozi nisu poslani u korisnički pretinac jer pretinac prima "
            + "samo PDF datoteke do 3 MB po poruci: ";

    static final String AKT_PREVELIK = "Akt je prevelik za korisnički pretinac i dostavlja se e-poštom.";

    /** Jedna poruka: akt i/ili ID-evi priloga. */
    record Dio(int dio, String naslov, String sadrzaj, boolean akt, List<Long> prilozi) {
    }

    private KpPorukaPlan() {
    }

    static List<Dio> izradi(KpAkt akt, long maxVelicina) {
        List<KpAkt.Prilog> pdf = new ArrayList<>();
        List<String> izostavljeni = new ArrayList<>();
        long nastavakTekst = bajtova(akt.naslov()) * 2 + 200;
        for (KpAkt.Prilog p : akt.prilozi()) {
            boolean stane = OMOTNICA + nastavakTekst + PO_PRIVITKU + base64(p.velicina()) <= maxVelicina;
            if (jePdf(p) && stane) {
                pdf.add(p);
            } else {
                izostavljeni.add(ime(p.naziv()));
            }
        }

        String sadrzaj = akt.sadrzaj();
        if (!izostavljeni.isEmpty()) {
            sadrzaj = sadrzaj + "\n\n" + NAPOMENA + String.join(", ", izostavljeni) + ".";
        }
        boolean sAktom = OMOTNICA + bajtova(akt.naslov()) + bajtova(sadrzaj) + PO_PRIVITKU
                + base64(akt.velicinaAkta()) <= maxVelicina;
        if (!sAktom) {
            sadrzaj = sadrzaj + "\n\n" + AKT_PREVELIK;
        }

        List<List<Long>> skupine = new ArrayList<>();
        List<Long> trenutna = new ArrayList<>();
        long zauzeto = OMOTNICA + bajtova(akt.naslov()) + bajtova(sadrzaj)
                + (sAktom ? PO_PRIVITKU + base64(akt.velicinaAkta()) : 0);
        for (KpAkt.Prilog p : pdf) {
            long velicina = PO_PRIVITKU + base64(p.velicina());
            if (zauzeto + velicina > maxVelicina) {
                skupine.add(trenutna);
                trenutna = new ArrayList<>();
                zauzeto = OMOTNICA + nastavakTekst;
            }
            trenutna.add(p.id());
            zauzeto += velicina;
        }
        skupine.add(trenutna);

        int n = skupine.size();
        List<Dio> dijelovi = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int dio = i + 1;
            String naslov = n == 1 ? akt.naslov() : akt.naslov() + " (" + dio + "/" + n + ")";
            String tekst = i == 0 ? sadrzaj
                    : "Prilozi uz poruku „" + akt.naslov() + "“ (" + dio + "/" + n + ").";
            dijelovi.add(new Dio(dio, naslov, tekst, i == 0 && sAktom, List.copyOf(skupine.get(i))));
        }
        return dijelovi;
    }

    /** Naziv privitka bez znakova koje KP ne dopušta ({@code \ / : * ? " < > |} i kontrolni). */
    static String ime(String naziv) {
        String cisto = naziv == null ? "" : naziv.codePoints()
                .filter(cp -> "\\/:*?\"<>|".indexOf(cp) < 0 && !Character.isISOControl(cp))
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString().strip();
        return cisto.isEmpty() ? "prilog.pdf" : cisto;
    }

    static boolean jePdf(KpAkt.Prilog p) {
        String ct = p.contentType() == null ? "" : p.contentType().toLowerCase(Locale.ROOT);
        String naziv = p.naziv() == null ? "" : p.naziv().toLowerCase(Locale.ROOT);
        return ct.startsWith("application/pdf") || (ct.isBlank() && naziv.endsWith(".pdf"));
    }

    static long base64(long bajtova) {
        return 4 * ((bajtova + 2) / 3);
    }

    private static long bajtova(String s) {
        return s == null ? 0 : s.getBytes(StandardCharsets.UTF_8).length;
    }

    /** Za log: koji prilozi su u kojoj poruci. */
    static String opis(List<Dio> dijelovi) {
        return dijelovi.stream()
                .map(d -> d.dio() + ":" + (d.akt() ? "akt+" : "") + d.prilozi().size())
                .collect(Collectors.joining(","));
    }
}
