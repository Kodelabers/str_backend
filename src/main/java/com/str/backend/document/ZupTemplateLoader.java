package com.str.backend.document;

import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Učitava i validira predloške akata s classpatha ({@code documents/hr/<slug>.txt}).
 *
 * <p>Provjera je namjerno na startu: predložak kojem nedostaje sekcija koju ZUP traži za taj tip
 * akta ruši podizanje aplikacije. Alternativa — otkriti to tek kad netko zatraži akt — znači da
 * bi iz sustava izašlo rješenje bez upute o pravnom lijeku, što po čl. 111. ZUP-a ide na štetu
 * tijela.
 *
 * <p>Uz {@code str.documents.reload=true} predlošci se čitaju pri svakom renderu, da se tekst
 * može mijenjati bez restarta. Za produkciju ostaje {@code false}.
 */
@Component
public class ZupTemplateLoader {

    private static final String BASE_PATH = "documents/hr/";
    private static final Pattern SECTION_HEADER = Pattern.compile("^\\[([A-Z_]+)]\\s*$");

    private final DocumentProperties properties;
    private final Map<StrDocumentType, ZupTemplate> cache = new EnumMap<>(StrDocumentType.class);

    public ZupTemplateLoader(DocumentProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void loadAll() {
        for (StrDocumentType type : StrDocumentType.templateBackedTypes()) {
            cache.put(type, read(type));
        }
    }

    public ZupTemplate get(StrDocumentType type) {
        if (!type.templateBacked()) {
            throw new DocumentTemplateException(
                    "Vrsta akta " + type + " nema predložak — renderira je vlastiti generator.");
        }
        if (properties.reload()) {
            return read(type);
        }
        ZupTemplate template = cache.get(type);
        if (template == null) {
            // Do ovoga dolazi samo ako se loader zaobiđe (npr. ručno instanciran u testu).
            template = read(type);
            cache.put(type, template);
        }
        return template;
    }

    private ZupTemplate read(StrDocumentType type) {
        String path = BASE_PATH + type.slug() + ".txt";
        String raw;
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new DocumentTemplateException("Predložak nije čitljiv: " + path, e);
        }
        Map<ZupSection, String> sections = parse(raw, path);
        validate(type, sections, path);
        return new ZupTemplate(type, sections);
    }

    private static Map<ZupSection, String> parse(String raw, String path) {
        Map<ZupSection, String> sections = new EnumMap<>(ZupSection.class);
        ZupSection current = null;
        List<String> buffer = new ArrayList<>();

        int lineNo = 0;
        for (String line : raw.split("\\R", -1)) {
            lineNo++;
            if (line.stripLeading().startsWith("#")) {
                continue;
            }
            Matcher header = SECTION_HEADER.matcher(line.strip());
            if (header.matches()) {
                flush(sections, current, buffer, path);
                current = section(header.group(1), path, lineNo);
                if (sections.containsKey(current)) {
                    throw new DocumentTemplateException(
                            "Sekcija [" + current + "] je u " + path + " navedena dvaput (redak "
                                    + lineNo + ").");
                }
                sections.put(current, "");
                buffer = new ArrayList<>();
                continue;
            }
            if (current == null) {
                if (!line.isBlank()) {
                    throw new DocumentTemplateException(
                            "Tekst izvan sekcije u " + path + ", redak " + lineNo
                                    + ": svaki sadržaj mora biti unutar [SEKCIJA] bloka.");
                }
                continue;
            }
            buffer.add(line);
        }
        flush(sections, current, buffer, path);
        if (sections.isEmpty()) {
            throw new DocumentTemplateException("Predložak " + path + " nema nijednu sekciju.");
        }
        return sections;
    }

    private static void flush(Map<ZupSection, String> sections, ZupSection current,
                              List<String> buffer, String path) {
        if (current == null) {
            return;
        }
        String body = String.join("\n", buffer).strip();
        if (body.isEmpty()) {
            throw new DocumentTemplateException(
                    "Sekcija [" + current + "] u " + path + " je prazna.");
        }
        sections.put(current, body);
    }

    private static ZupSection section(String name, String path, int lineNo) {
        try {
            return ZupSection.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new DocumentTemplateException(
                    "Nepoznata sekcija [" + name + "] u " + path + ", redak " + lineNo
                            + ". Dopuštene: " + java.util.Arrays.toString(ZupSection.values()));
        }
    }

    private static void validate(StrDocumentType type, Map<ZupSection, String> sections,
                                 String path) {
        List<ZupSection> missing = type.requiredSections().stream()
                .filter(s -> !sections.containsKey(s))
                .toList();
        if (!missing.isEmpty()) {
            throw new DocumentTemplateException(
                    "Predlošku " + path + " nedostaju sekcije obvezne za " + type + ": " + missing
                            + ". Vidi StrDocumentType.requiredSections() i docs/ZUP-predlosci.md.");
        }
    }
}
