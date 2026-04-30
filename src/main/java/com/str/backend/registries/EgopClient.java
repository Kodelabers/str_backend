package com.str.backend.registries;

import java.time.Instant;

/**
 * STR funkcionalna specifikacija §3 (osnovni flow): eGOP — sustav urudžbiranja
 * pisarnice. Dva poziva tijekom registracije:
 *   1. {@link #rezervirajUrudzbeniBroj()} — vraća službeni urudžbeni broj koji se
 *      utiskuje u PDF zahtjeva.
 *   2. {@link #posaljiZahtjev(String, byte[])} — predaje finalni PDF (s utisnutim
 *      brojem) eGOP-u i potvrđuje uredan unos u urudžbenu evidenciju.
 */
public interface EgopClient {

    record UrudzbeniBroj(String klasa, String urbroj, Instant datumUrudzbe) {

        public String formatiran() {
            return "KLASA: " + klasa + ", URBROJ: " + urbroj;
        }
    }

    record PotvrdaUrudzbiranja(String urudzbeniBroj, Instant datumPotvrde) {}

    UrudzbeniBroj rezervirajUrudzbeniBroj();

    PotvrdaUrudzbiranja posaljiZahtjev(String urudzbeniBroj, byte[] pdf);
}
