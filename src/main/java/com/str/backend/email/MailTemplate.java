package com.str.backend.email;

import java.util.Set;

/**
 * Predlošci poruka e-pošte, u {@code documents/mail/<slug>.html}.
 *
 * <p><b>E-pošta nije dostava.</b> Po čl. 94. st. 6 ZUP-a osobnom dostavom smatra se dostava u
 * korisnički pretinac; mail je samo uljudna obavijest da je akt tamo. Zato nijedan predložak
 * ne navodi rokove pravnog lijeka kao da teku od maila — inače bi stranka računala rok od
 * krivog dana.
 *
 * <p>{@link #placeholders()} je <b>ugovor</b> između predloška i {@link SmtpEmailService}:
 * popisuje oznake koje servis zna napuniti za tu poruku. {@link MailTemplateLoader} ga
 * provjerava na startu. Bez toga bi oznaka dodana u predložak prošla nezapaženo — na
 * {@code local}/{@code mock}/{@code test} profilu radi {@link LoggingEmailService}, koji
 * predloške uopće ne renderira, pa bi se kvar pokazao tek u produkciji.
 */
public enum MailTemplate {

    ODOBRENJE("odobrenje", Keys.ODOBRENJE),
    ODBIJANJE("odbijanje", Keys.SAMO_IME),
    RB_IZDAN("rb-izdan", Keys.RB_IZDAN),
    PRIJEDLOG_SUSPENZIJE("prijedlog-suspenzije", Keys.LIFECYCLE),
    SUSPENZIJA("suspenzija", Keys.LIFECYCLE),
    OBUSTAVA_SUSPENZIJE("obustava-suspenzije", Keys.LIFECYCLE),
    REAKTIVACIJA("reaktivacija", Keys.LIFECYCLE),
    POVLACENJE("povlacenje", Keys.LIFECYCLE),
    OPOZIV("opoziv", Keys.LIFECYCLE);

    /**
     * Skupovi žive u ugniježđenom razredu jer se enum konstante inicijaliziraju <b>prije</b>
     * statičkih polja samog enuma — polje deklarirano ispod konstanti bilo bi {@code null} u
     * trenutku njihove izgradnje.
     */
    static final class Keys {
        /** Oznake koje {@code EmailTemplates} dodaje svakoj poruci, bez obzira na predložak. */
        static final Set<String> ZAJEDNICKE =
                Set.of("klauzula.dostava", "gumb.prijava", "gumb.logIn");

        static final Set<String> SAMO_IME = Set.of("ime");
        static final Set<String> ODOBRENJE = Set.of("ime", "korisnickoIme");
        static final Set<String> RB_IZDAN = Set.of("ime", "rn");
        /** Sve što {@code SmtpEmailService} puni iz {@link RnLifecycleMail}. */
        static final Set<String> LIFECYCLE = Set.of("ime", "rn", "objekt", "razlog", "rok");

        private Keys() {
        }
    }

    private final String slug;
    private final Set<String> placeholders;

    MailTemplate(String slug, Set<String> placeholders) {
        this.slug = slug;
        this.placeholders = placeholders;
    }

    public String slug() {
        return slug;
    }

    /** Oznake koje predložak smije koristiti; {@link Keys#ZAJEDNICKE} su uvijek dopuštene. */
    public Set<String> placeholders() {
        return placeholders;
    }
}
