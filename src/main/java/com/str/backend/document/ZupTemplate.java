package com.str.backend.document;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * Razlomljen predložak jednog akta — sekcija → sirovi tekst (s još nezamijenjenim
 * {@code ${...}} oznakama).
 *
 * <p>Redoslijed sekcija u datoteci je nebitan: renderer ih ispisuje poretkom konstanti
 * {@link ZupSection}, pa premještanje bloka u predlošku ne može promijeniti izgled akta.
 */
public final class ZupTemplate {

    private final StrDocumentType type;
    private final Map<ZupSection, String> sections;

    ZupTemplate(StrDocumentType type, Map<ZupSection, String> sections) {
        this.type = type;
        this.sections = Collections.unmodifiableMap(new EnumMap<>(sections));
    }

    public StrDocumentType type() {
        return type;
    }

    public Optional<String> section(ZupSection section) {
        return Optional.ofNullable(sections.get(section));
    }

    public boolean has(ZupSection section) {
        return sections.containsKey(section);
    }

    /** Za dijagnostiku u porukama o grešci. */
    public String origin() {
        return "documents/hr/" + type.slug() + ".txt";
    }
}
