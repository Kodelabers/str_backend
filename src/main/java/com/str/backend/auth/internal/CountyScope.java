package com.str.backend.auth.internal;

import com.str.backend.address.CountyNames;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Opseg podataka službenika: jedna županija ili cijela RH. Za svakoga tko nije službenik s
 * {@link InternalAccessService#RN_MANAGEMENT} opseg je cijela RH — iznajmljivače ograničava
 * vlasništvo (OIB), ne ovo.
 *
 * @param outcome     kako je opseg utvrđen ({@link OfficialCountyResolver})
 * @param subjectName naziv interne organizacije službenika; {@code null} kad je nema
 * @param countyId    {@code rpj_dgu.zupanije.id}; {@code null} = cijela RH
 * @param zuRb        {@code rpj_dgu.zupanije.zu_rb}; {@code null} = cijela RH
 * @param countyName  {@code rpj_dgu.zupanije.zu_ime}; {@code null} = cijela RH
 */
public record CountyScope(Outcome outcome, String subjectName, Long countyId, Integer zuRb, String countyName) {

    public enum Outcome {
        /** Nije službenik — opseg se ne utvrđuje. */
        NOT_OFFICIAL,
        /** Organizacija županije s nadležnošću u jednoj županiji. */
        COUNTY,
        /** Ministarstvo — cijela RH. */
        MINISTRY,
        /** Bez organizacije, druga vrsta organizacije ili županija bez nadležnosti — cijela RH. */
        NONE,
        /** Nadležnost organizacije županije u više županija — ne zna se koja, pa cijela RH. */
        AMBIGUOUS,
        /** Greška baze ili nepoznata županija — cijela RH. */
        ERROR
    }

    public static CountyScope all(Outcome outcome) {
        return new CountyScope(outcome, null, null, null, null);
    }

    public static CountyScope ministry(String subjectName) {
        return new CountyScope(Outcome.MINISTRY, subjectName, null, null, null);
    }

    public static CountyScope county(String subjectName, Long countyId, Integer zuRb, String countyName) {
        return new CountyScope(Outcome.COUNTY, subjectName, countyId, zuRb, countyName);
    }

    /**
     * Ograničen na jednu županiju. Samo uz ishod {@link Outcome#COUNTY} i naziv koji se može
     * usporediti — prazan naziv ne smije ni ograničiti ni srušiti usporedbu ({@link #covers}).
     */
    public boolean restricted() {
        return outcome == Outcome.COUNTY && CountyNames.key(countyName) != null;
    }

    /**
     * Usporedivi oblik naziva županije ({@link CountyNames#key}) za filtre nad
     * {@code accommodation.county}; {@code null} kad opseg nije ograničen.
     */
    public String key() {
        return restricted() ? CountyNames.key(countyName) : null;
    }

    /** Pripada li županija zadana nazivom (kako je zapisana na smještaju ili iznajmljivaču) opsegu. */
    public boolean covers(String county) {
        return !restricted() || key().equals(CountyNames.key(county));
    }

    /**
     * Nazivi županije opsega u obliku u kojem su zapisani u bazi ({@code accommodation.county}),
     * za filtar {@code IN (:nazivi)}. Isti podatak stoji u više oblika
     * („Splitsko-dalmatinska županija", „Splitsko-dalmatinska", velika slova), a uspoređuje se u
     * Javi ({@link CountyNames#key}): {@code LOWER()} u PostgreSQL-u s lokalom {@code C} ne spušta
     * Ž, Š, Č, pa bi normalizacija u upitu tiho promašila velika slova iz eTurizma.
     *
     * @param stored svi različiti zapisani nazivi
     * @return {@code null} kad opseg nije ograničen; nikad prazno — kad nijedan zapis ne odgovara,
     *         sam naziv županije, koji tada ne pogađa ništa
     */
    public List<String> matching(Collection<String> stored) {
        if (!restricted()) {
            return null;
        }
        List<String> names = stored.stream().filter(Objects::nonNull).filter(this::covers).distinct().toList();
        return names.isEmpty() ? List.of(countyName) : names;
    }
}
