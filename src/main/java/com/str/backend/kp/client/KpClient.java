package com.str.backend.kp.client;

import com.str.backend.kp.KpPrimatelj;

/**
 * Sustav za zaprimanje poruka (SZP) APIS IT-a — slanje u korisnički pretinac i dohvat aktivnosti
 * nad poslanom porukom. Domenski potpis; preslikavanje na GSB omotnicu i poslovne sheme FO/PS
 * ostaje u {@link KpSoapClient}.
 *
 * <p>Odbijanje (šifra greške) <b>nije</b> iznimka nego ishod u odgovoru; iznimka
 * ({@link KpTransportException}) znači da ishod nije poznat — mreža, TLS, HTTP, SOAP fault ili
 * odgovor koji se ne može pročitati.
 */
public interface KpClient {

    KpOdgovor posalji(KpPoruka poruka);

    /** Samo za poruke s pravnim učinkom (servisi 128/129). */
    KpAktivnost aktivnost(KpPrimatelj primatelj, String uuidPoruke);

    /** Provjera veze ({@code echo}); vraća odgovor SZP-a. */
    String echo(String poruka);
}
