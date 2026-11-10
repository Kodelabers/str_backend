package com.str.backend.lookup;

/**
 * Vrsta subjekta prema pravilu o tome što smije prijaviti ({@code str_rn.subject_category}, T13).
 * Kodovi su fiksni, pa je enum; pravilo koja kategorija smije koju skupinu živi u tablici
 * {@code str_rn.subject_category_object_group}.
 *
 * <ul>
 *   <li>{@link #FIZICKA_OSOBA} — NIAS osoba u svoje ime i non-EU iznajmljivač: vrste domaćinstva;</li>
 *   <li>{@link #OPG} — OPG iz e-Ovlaštenja: vrste ugostiteljskih usluga u OPG-u;</li>
 *   <li>{@link #OSTALI_POSLOVNI_SUBJEKT} — tvrtka i obrt iz e-Ovlaštenja: skupine objekata.</li>
 * </ul>
 */
public enum SubjectCategory {
    FIZICKA_OSOBA,
    OPG,
    OSTALI_POSLOVNI_SUBJEKT
}
