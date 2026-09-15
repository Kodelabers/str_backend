package com.str.backend.address;

import com.str.backend.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/**
 * Katastar objekta — katastarska općina, broj čestice i registarska šifra — izveden iz adresnog
 * registra, a ne preuzet iz zahtjeva.
 *
 * <p>Zašto postoji: katastar ide u ZUP akte i izvještaje, a dolazio je iz preglednika i spremao se
 * doslovno, pa je korisnik mogao poslati bilo koju katastarsku općinu. Isti razlog zbog kojeg
 * postoji {@code FacilityClaimVerifier}: sve što potječe iz registra, a putuje kroz formu, može se
 * urediti prije submita.
 *
 * <p>Pravila:
 * <ul>
 *   <li><b>Bez {@code kucniBrojId}</b> (adresa nije odabrana iz registra) nema čega provjeriti:
 *       općina i šifra ostaju prazne, čestica je ono što je korisnik upisao.</li>
 *   <li><b>S {@code kucniBrojId}</b> redak mora postojati i pripadati ulici i kućnom broju iz
 *       zahtjeva. Fronta ih postavlja iz istog retka, pa ispravan zahtjev uvijek prolazi —
 *       nepodudaranje znači podmetnut id i daje 400.</li>
 *   <li><b>Općina</b> dolazi isključivo iz registra.</li>
 *   <li><b>Čestica:</b> kad je registar ima, vrijedi registar, a drukčija poslana vrijednost daje
 *       400 — povučeni podaci se ne mijenjaju, za to postoji zahtjev za promjenom (sastanak
 *       10.09.2026., t. 3). Kad je registar nema (na CDU 28,7 % adresa), prihvaća se upisana.</li>
 *   <li><b>Šifra</b> ({@code accommodation.house_number_code}) je sirova registarska vrijednost,
 *       kako changeset 047 i predviđa („stable šifra"), neovisno o tome što je klijent poslao.</li>
 * </ul>
 */
@Service
public class CadastreResolver {

    /** Katastar za spremanje na {@code accommodation}; bilo koja komponenta smije biti {@code null}. */
    public record Cadastre(String katOpcinaNaziv, String kcCestica, String sifra) {}

    private final HouseNumberRepository houseNumberRepository;

    public CadastreResolver(HouseNumberRepository houseNumberRepository) {
        this.houseNumberRepository = houseNumberRepository;
    }

    @Transactional(readOnly = true)
    public Cadastre resolve(Long kucniBrojId, String street, String streetNumber, String submittedKcBroj) {
        String upisana = blankToNull(submittedKcBroj);
        if (kucniBrojId == null) {
            return new Cadastre(null, upisana, null);
        }

        HouseNumberRepository.CadastreRow row = houseNumberRepository.findCadastreById(kucniBrojId)
                .orElseThrow(() -> new BusinessException("error.cadastre.houseNumber.unknown"));

        if (!sameText(row.getBroj(), streetNumber) || !sameText(row.getNazivUlice(), street)) {
            throw new BusinessException("error.cadastre.houseNumber.mismatch");
        }

        String izRegistra = HouseNumberEntity.cestica(row.getKcBroj());
        if (izRegistra != null && upisana != null && !sameText(izRegistra, upisana)) {
            throw new BusinessException("error.cadastre.parcel.mismatch");
        }

        return new Cadastre(
                blankToNull(row.getKatOpcinaNaziv()),
                izRegistra != null ? izRegistra : upisana,
                blankToNull(row.getKcBroj()));
    }

    /**
     * Ista normalizacija kao u {@code FacilityClaimVerifier}: bjeline se sažimaju, velika i mala
     * slova se ne razlikuju — registar ne piše nazive dosljedno.
     */
    private static boolean sameText(String a, String b) {
        return a != null && b != null && normalize(a).equals(normalize(b));
    }

    private static String normalize(String value) {
        return value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
