package com.str.backend.auth.nias;

public record FacilityResponse(
        String id,
        String naziv,
        String vrstaNaziv,
        Integer brKreveta,
        String zupanijaNaziv,
        String opcinaNaziv,
        String naseljeNaziv,
        String ulicaNaziv,
        String kucniBrojNaziv
) {}
