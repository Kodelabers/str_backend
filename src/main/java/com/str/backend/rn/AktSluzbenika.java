package com.str.backend.rn;

import com.str.backend.common.Strings;
import com.str.backend.exception.BusinessException;

import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Što službenik daje uz akt obustave postupka ili reaktivacije ({@link RnService#revokeProposal},
 * {@link RnService#reactivate}, {@link RnService#revokeWithdrawalProposal}, {@link RnService#reinstate}) —
 * ručno ili uvažavanjem očitovanja odnosno prigovora.
 *
 * @param rucni        ručni akt županijskog službenika (T16); {@code null} = akt kroz eGOP
 * @param obrazlozenje obrazloženje službenika (UAT 13.11.): akt ga ispisuje kao sekciju „Obrazloženje" samo kad
 *                     je upisano; sirovo, čisti ga {@link #ocistiObrazlozenje}
 * @param prilogIds    prilozi službenika uz akt (id-evi iz {@code POST /api/rn/attachments}); vežu se uz zapis
 *                     prijelaza kao kod prijedloga (T17); {@code null} = bez priloga
 */
public record AktSluzbenika(RucniAkt rucni, String obrazlozenje, Collection<Long> prilogIds) {

    /** Akt bez ručnih oznaka, obrazloženja i priloga. */
    public static final AktSluzbenika NEMA = new AktSluzbenika(null, null, List.of());

    /**
     * Najdulje obrazloženje — širina {@code registration_number_log.akt_obrazlozenje}, ista kao bilješke uz
     * odluku o prigovoru ({@code suspension_objection.resolution_note}), iz koje ga uvažavanje prenosi.
     */
    public static final int MAX_OBRAZLOZENJE = 1000;

    /**
     * Kontrolni znakovi osim prijeloma retka i tabulatora: NUL PostgreSQL odbija u tekstu (500), a ostali na
     * aktu nemaju smisla. Prijelomi ostaju — njima službenik dijeli odlomke.
     */
    private static final Pattern KONTROLNI_ZNAKOVI = Pattern.compile("[\\p{Cntrl}&&[^\\n\\r\\t]]");

    public AktSluzbenika {
        prilogIds = prilogIds == null ? List.of() : prilogIds;
    }

    /** Samo ručne oznake (T16), bez obrazloženja i priloga. */
    public static AktSluzbenika rucni(RucniAkt rucni) {
        return new AktSluzbenika(rucni, null, List.of());
    }

    /**
     * Obrazloženje za spremanje: bez kontrolnih znakova (osim prijeloma i tabulatora), obrezano; {@code null} kad
     * je prazno. Dulje od {@value #MAX_OBRAZLOZENJE} znakova je 400 ({@code error.rn.obrazlozenje.tooLong}), a ne
     * 500 iz baze.
     */
    public static String ocistiObrazlozenje(String obrazlozenje) {
        if (obrazlozenje == null) {
            return null;
        }
        String clean = Strings.blankToNull(KONTROLNI_ZNAKOVI.matcher(obrazlozenje).replaceAll(""));
        if (clean != null && clean.length() > MAX_OBRAZLOZENJE) {
            throw new BusinessException("error.rn.obrazlozenje.tooLong");
        }
        return clean;
    }
}
