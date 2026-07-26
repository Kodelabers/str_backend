package com.str.backend.document;

import com.str.backend.domain.RnStatus;
import com.str.backend.domain.RnTrigger;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Hrvatski nazivi enum vrijednosti i rezervne fraze za akte i mailove.
 *
 * <p>Čita se {@link Reader}-om u UTF-8, ne {@code InputStream}-om — {@code Properties.load}
 * s {@code InputStream}-om pretpostavlja ISO-8859-1 i pojeo bi dijakritiku.
 */
@Component
public class DocumentLabels {

    private static final String PATH = "documents/hr/labels.properties";

    private final Properties labels = new Properties();

    public DocumentLabels() {
        try (Reader reader = new InputStreamReader(
                new ClassPathResource(PATH).getInputStream(), StandardCharsets.UTF_8)) {
            labels.load(reader);
        } catch (IOException e) {
            throw new DocumentTemplateException("Nedostaje ili nije čitljiv " + PATH, e);
        }
    }

    public String get(String key) {
        String value = labels.getProperty(key);
        if (value == null) {
            throw new DocumentTemplateException("Nedostaje natpis '" + key + "' u " + PATH);
        }
        return value;
    }

    public String format(String key, Object... args) {
        return String.format(get(key), args);
    }

    public String trigger(RnTrigger trigger) {
        return trigger == null ? get("razlog.nijeNaveden") : get("trigger." + trigger.name());
    }

    public String status(RnStatus status) {
        return status == null ? get("vrijednost.nepoznata") : get("status." + status.name());
    }
}
