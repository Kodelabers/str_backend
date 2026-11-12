package com.str.backend.pdf;

import com.str.backend.document.FilingReference;
import com.str.backend.lessor.LessorEntity;

import java.time.Instant;
import java.util.List;

/**
 * Podaci za PDF podneska stranke na akt (očitovanje, prigovor) — skraćeni obrazac zahtjeva.
 *
 * @param tijelo             adresat, redak po redak: Ministarstvo ({@code DocumentProperties.tijelo}) ili
 *                           županija službenika (naziv organizacije i jedinica)
 * @param naslov             naslov sekcije, npr. „PRIGOVOR NA SUSPENZIJU REGISTRACIJSKOG BROJA"
 * @param vrstaPodneska      naziv vrste pismena ({@code StrDocumentType.podnesakNaAkt})
 * @param zaprimljeno        predaja prigovora ({@code submitted_at}); ispisuje se po hrvatskom vremenu
 * @param filing             KLASA i URBROJ pismena; {@code null} ili prazni dijelovi se izostavljaju
 * @param registrationNumber registracijski broj
 * @param odnosiSeNa         akt na koji podnesak odgovara, uz njegovu KLASU i URBROJ kad su poznati
 * @param lessor             podnositelj (iznajmljivač RB-a)
 * @param groupName          skupina objekta; {@code null} kad vrsta nije poznata
 * @param prilozi            nazivi svih datoteka koje je podnositelj učitao, glavni dokument prvi
 */
public record PodnesakPdfContext(
        List<String> tijelo,
        String naslov,
        String vrstaPodneska,
        Instant zaprimljeno,
        FilingReference filing,
        String registrationNumber,
        String odnosiSeNa,
        LessorEntity lessor,
        String groupName,
        String street,
        String streetNumber,
        String postalCode,
        String cityName,
        List<String> prilozi
) {

    public PodnesakPdfContext {
        tijelo = tijelo == null ? List.of() : List.copyOf(tijelo.stream().map(t -> t == null ? "" : t).toList());
        prilozi = prilozi == null ? List.of() : List.copyOf(prilozi);
    }
}
