package com.str.backend.auth.nias;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Pravna osoba u čije ime NIAS korisnik trenutno djeluje, nakon što su e-Ovlaštenja potvrdila
 * da je njezin zakonski zastupnik. Drži se isključivo u server-side sesiji
 * ({@link ActingSubjectService#SESSION_KEY}) — klijent je nikad ne šalje.
 *
 * <p>U sesiju ide kao {@link #toSessionValue()}, mapa samih JDK tipova, a ne kao ovaj record: sesija
 * je zajednička sa str-internal-api ({@code session.spring_session}), a servis koji naše klase nema
 * na takvom atributu puca. {@link Serializable} ostaje samo da se sesije spremljene prije te
 * promjene i dalje mogu učitati ({@link #fromSessionValue}); nakon što isteknu, može se ukloniti.
 *
 * @param functions        nazivi funkcija iz e-Zastupanja (npr. „Direktor")
 * @param representativeOib OIB NIAS osobe koja je provjerena — subjekt vrijedi samo za nju
 */
public record ActingSubject(
        String legalOib,
        String legalName,
        List<String> functions,
        String representativeOib,
        String representativeFirstName,
        String representativeLastName,
        Instant verifiedAt
) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public ActingSubject {
        functions = List.copyOf(functions);
    }

    /** Oblik za sesiju: samo {@code String}, {@code ArrayList} i {@code HashMap}. */
    HashMap<String, Object> toSessionValue() {
        HashMap<String, Object> value = new HashMap<>();
        value.put("legalOib", legalOib);
        value.put("legalName", legalName);
        value.put("functions", new ArrayList<>(functions));
        value.put("representativeOib", representativeOib);
        value.put("representativeFirstName", representativeFirstName);
        value.put("representativeLastName", representativeLastName);
        value.put("verifiedAt", verifiedAt.toString());
        return value;
    }

    /** Iz sesije; nepoznat ili oštećen oblik znači „subjekt nije odabran". */
    static Optional<ActingSubject> fromSessionValue(Object value) {
        if (value instanceof ActingSubject legacy) {
            return Optional.of(legacy);
        }
        if (!(value instanceof Map<?, ?> map)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new ActingSubject(
                    (String) map.get("legalOib"),
                    (String) map.get("legalName"),
                    ((List<?>) map.get("functions")).stream().map(String.class::cast).toList(),
                    (String) map.get("representativeOib"),
                    (String) map.get("representativeFirstName"),
                    (String) map.get("representativeLastName"),
                    Instant.parse((String) map.get("verifiedAt"))));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
