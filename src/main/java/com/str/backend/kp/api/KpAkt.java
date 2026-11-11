package com.str.backend.kp.api;

import java.util.List;
import java.util.UUID;

/**
 * Akt koji treba dostaviti u korisnički pretinac preko KP API-ja ({@link KpPorukaService#zabiljezi}).
 *
 * @param pismenoId      {@code egop_pismeno.id} — iz njega se pri slanju čita PDF akta
 * @param rn             registracijski broj
 * @param oib            OIB primatelja, fizičke osobe (pravna osoba i non-EU idu samo e-poštom)
 * @param vrstaAkta      ime {@code StrDocumentType}, za šifru poruke po aktu
 * @param izdavateljZuRb {@code zu_rb} županije koja je akt izdala
 * @param naslov         naslov poruke u pretincu
 * @param sadrzaj        tekst poruke (običan tekst)
 * @param klasa          KLASA akta
 * @param urBroj         URBROJ akta
 * @param nazivAkta      naziv PDF datoteke akta
 * @param velicinaAkta   veličina PDF-a akta u bajtovima
 * @param prilozi        prilozi uz akt, redom kojim se prilažu
 */
public record KpAkt(UUID pismenoId, String rn, String oib, String vrstaAkta, int izdavateljZuRb,
                    String naslov, String sadrzaj, String klasa, String urBroj,
                    String nazivAkta, long velicinaAkta, List<Prilog> prilozi) {

    public KpAkt {
        prilozi = prilozi == null ? List.of() : List.copyOf(prilozi);
    }

    /** Prilog bez sadržaja ({@code pismeno_prilog}); sadržaj se čita pri slanju. */
    public record Prilog(long id, String naziv, String contentType, long velicina) {
    }
}
