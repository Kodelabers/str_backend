package com.str.backend.email;

/**
 * Jedna obavijest o promjeni statusa registracijskog broja.
 *
 * @param pdf            akt u privitku; {@code null} kad ga nije bilo moguće renderirati
 * @param dostavaMailom  je li e-pošta <b>kanal dostave</b> (non-EU iznajmljivač bez pristupa
 *                       korisničkom pretincu) ili samo obavijest uz dostavu u pretinac.
 *                       Određuje klauzulu na dnu poruke — tvrditi da je akt dostavljen u
 *                       pretinac nekome tko ga nema bilo bi netočno, a i obrnuto: non-EU
 *                       stranci rokovi teku upravo od ove poruke (čl. 94. st. 4 ZUP-a).
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
        boolean dostavaMailom
) {
}
