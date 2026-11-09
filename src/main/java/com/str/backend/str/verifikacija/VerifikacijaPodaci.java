package com.str.backend.str.verifikacija;

import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.accommodation.AccommodationRepository;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.lessor.LessorRepository;
import com.str.backend.lookup.AccommodationTypeEntity;
import com.str.backend.lookup.AccommodationTypeRepository;
import com.str.backend.request.SubmissionRepository;
import com.str.backend.rn.RnRepository;
import com.str.backend.str.FacilityClaimVerifier;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Sastavlja {@code korisnicka_obavijest.podaci} za redak outboxa: iznajmljivač iz predaje, vrsta,
 * skupina i adresa iz smještaja RB-a, a naziv objekta iz retka (za objekt iz eTurizma je eTurizmov).
 *
 * <p>Čita se pri upisu, u transakciji pokušaja, pa ni stariji retci na čekanju ne trebaju dodatne
 * stupce. Što se ne nađe ostaje {@code null}; eTurizam to dopušta, a obavijest bez tih podataka i
 * dalje nosi tekst s registracijskim brojem.
 */
@Component
class VerifikacijaPodaci {

    private final RnRepository rnRepository;
    private final AccommodationRepository accommodationRepository;
    private final AccommodationTypeRepository accommodationTypeRepository;
    private final SubmissionRepository submissionRepository;
    private final LessorRepository lessorRepository;

    VerifikacijaPodaci(RnRepository rnRepository,
                       AccommodationRepository accommodationRepository,
                       AccommodationTypeRepository accommodationTypeRepository,
                       SubmissionRepository submissionRepository,
                       LessorRepository lessorRepository) {
        this.rnRepository = rnRepository;
        this.accommodationRepository = accommodationRepository;
        this.accommodationTypeRepository = accommodationTypeRepository;
        this.submissionRepository = submissionRepository;
        this.lessorRepository = lessorRepository;
    }

    String sastavi(VerifikacijaOutboxEntity zapis) {
        AccommodationEntity smjestaj = rnRepository.findById(zapis.getRn())
                .flatMap(rn -> accommodationRepository.findById(rn.getAccommodationId()))
                .orElse(null);
        AccommodationTypeEntity vrsta = smjestaj == null || smjestaj.getAccommodationTypeId() == null ? null
                : accommodationTypeRepository.findById(smjestaj.getAccommodationTypeId()).orElse(null);
        LessorEntity iznajmljivac = submissionRepository.findById(zapis.getSubmissionId())
                .flatMap(predaja -> lessorRepository.findById(predaja.getLessorId()))
                .orElse(null);

        return VerifikacijaObavijest.podaci(subjekt(iznajmljivac), new VerifikacijaObavijest.Objekt(
                vrsta == null ? null : vrsta.getGroup(),
                vrsta == null ? null : vrsta.getName(),
                zapis.getObjektNaziv(),
                adresa(smjestaj)));
    }

    /** Za pravnu osobu {@code lessorOib} je OIB tvrtke, a naziv njen naziv; inače ime i prezime. */
    private static VerifikacijaObavijest.Subjekt subjekt(LessorEntity iznajmljivac) {
        if (iznajmljivac == null) {
            return null;
        }
        String tvrtka = FacilityClaimVerifier.knownOrNull(iznajmljivac.getLegalEntityName());
        return new VerifikacijaObavijest.Subjekt(iznajmljivac.getLessorOib(),
                tvrtka != null ? tvrtka : spoji(" ", iznajmljivac.getFirstName(), iznajmljivac.getLastName()));
    }

    /** Oblik kao u eTurizmovim obavijestima: „naselje, ulica broj, poštanski broj mjesto". */
    private static String adresa(AccommodationEntity smjestaj) {
        if (smjestaj == null) {
            return null;
        }
        return spoji(", ", smjestaj.getSettlement(),
                spoji(" ", smjestaj.getStreet(), smjestaj.getStreetNumber()),
                spoji(" ", smjestaj.getPostalCode(), smjestaj.getCity()));
    }

    private static String spoji(String razmak, String... dijelovi) {
        String spojeno = Stream.of(dijelovi)
                .map(FacilityClaimVerifier::knownOrNull)
                .filter(Objects::nonNull)
                .collect(Collectors.joining(razmak));
        return spojeno.isEmpty() ? null : spojeno;
    }
}
