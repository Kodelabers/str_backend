package com.str.backend.email;

import java.util.List;

/**
 * Jedna obavijest o promjeni statusa registracijskog broja.
 *
 * @param pdf            akt u privitku; {@code null} kad ga nije bilo moguće renderirati
 * @param dostava        je li e-pošta <b>kanal dostave</b> (non-EU iznajmljivač bez pristupa
 *                       korisničkom pretincu), samo obavijest uz dostavu u pretinac, ili akt
 *                       uopće ne ide u pretinac (ulazno pismeno — opoziv). Određuje klauzulu
 *                       na dnu poruke — tvrditi da je akt dostavljen u pretinac nekome tko ga
 *                       nema bilo bi netočno, a i obrnuto: non-EU stranci rokovi teku upravo
 *                       od ove poruke (čl. 94. st. 4 ZUP-a).
 * @param prilozi        prilozi akta (službenika) u privitku, uz akt
 * @param priloziIzvanPoruke nazivi priloga akta koji zbog ukupne veličine nisu u privitku — poruka
 *                       ih samo navodi i upućuje na korisnički pretinac odnosno aplikaciju
 */
public record RnLifecycleMail(
        MailTemplate template,
        String to,
        String ime,
        String rn,
        String objekt,
        String razlog,
        String rok,
        byte[] pdf,
        Dostava dostava,
        List<MailPrilog> prilozi,
        List<String> priloziIzvanPoruke
) {

    public RnLifecycleMail {
        prilozi = prilozi == null ? List.of() : List.copyOf(prilozi);
        priloziIzvanPoruke = priloziIzvanPoruke == null ? List.of() : List.copyOf(priloziIzvanPoruke);
    }

    /** Obavijest bez priloga akta. */
    public RnLifecycleMail(MailTemplate template, String to, String ime, String rn, String objekt, String razlog,
                           String rok, byte[] pdf, Dostava dostava) {
        this(template, to, ime, rn, objekt, razlog, rok, pdf, dostava, List.of(), List.of());
    }
}
