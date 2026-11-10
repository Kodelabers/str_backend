package com.str.backend.registries.eovlastenja;

import com.str.backend.lookup.SubjectCategory;

import java.util.Arrays;
import java.util.Optional;

/**
 * Pravni oblik subjekta u čije ime osoba djeluje, prema registru iz e-Ovlaštenja ({@code IZVOR_REG}).
 * Određuje što subjekt u STR-u smije prijaviti (T13): OPG vrste ugostiteljskih usluga u OPG-u, a
 * tvrtka i obrt skupine objekata.
 *
 * <p><b>Šifre obrta i OPG-a nisu potvrđene.</b> Dokumentacija e-Ovlaštenja koju imamo navodi samo
 * „obrt i OPG (IZVOR_REG 2/3)" ({@code docs/EOVLASTENJA.md}, E8); mapiranje 2 = obrt, 3 = OPG
 * provjerava se na CDU-u stvarnim odabirom kroz navigacijsku traku (log {@code acting_subject_selected
 * izvorReg=}). Dok nije potvrđeno, to nije za merge.
 */
public enum LegalForm {
    /** Tvrtka iz sudskog registra. */
    TVRTKA("1", SubjectCategory.OSTALI_POSLOVNI_SUBJEKT),
    OBRT("2", SubjectCategory.OSTALI_POSLOVNI_SUBJEKT),
    OPG("3", SubjectCategory.OPG);

    private final String izvorReg;
    private final SubjectCategory subjectCategory;

    LegalForm(String izvorReg, SubjectCategory subjectCategory) {
        this.izvorReg = izvorReg;
        this.subjectCategory = subjectCategory;
    }

    /** Šifra registra u porukama e-Ovlaštenja. */
    public String izvorReg() {
        return izvorReg;
    }

    /** Što subjekt ovog oblika smije prijaviti. */
    public SubjectCategory subjectCategory() {
        return subjectCategory;
    }

    /** Oblik za šifru registra; prazno za registar koji ne prihvaćamo (e-Punomoći, tijela). */
    public static Optional<LegalForm> fromIzvorReg(String izvorReg) {
        String value = izvorReg == null ? null : izvorReg.trim();
        return Arrays.stream(values()).filter(f -> f.izvorReg.equals(value)).findFirst();
    }
}
