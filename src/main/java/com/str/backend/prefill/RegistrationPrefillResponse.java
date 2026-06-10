package com.str.backend.prefill;

public record RegistrationPrefillResponse(
        String oib,
        String ime,
        String prezime,
        Integer brojKreveta,
        Integer brojGostiju,
        String zupanijaNaziv,
        String opcinaNaziv,
        String naseljeNaziv,
        String ulicaNaziv,
        String kucniBrojNaziv
) {
}
