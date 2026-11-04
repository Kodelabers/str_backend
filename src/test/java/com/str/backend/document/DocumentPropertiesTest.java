package com.str.backend.document;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentPropertiesTest {

    private static DocumentProperties.Tijelo tijelo(String naziv) {
        return new DocumentProperties.Tijelo(naziv, null, null, null, null, null);
    }

    @Test
    void nazivTijela_uvijekPocinjeVelikimSlovom() {
        assertThat(tijelo("ministarstvo turizma i sporta").naziv())
                .isEqualTo("Ministarstvo turizma i sporta");
        assertThat(tijelo("  ministarstvo turizma i sporta ").naziv())
                .isEqualTo("Ministarstvo turizma i sporta");
        assertThat(tijelo("čelnik tijela").naziv()).isEqualTo("Čelnik tijela");
    }

    @Test
    void nazivTijela_ostatakOstajeKakoJeUpisan() {
        assertThat(tijelo("Ministarstvo turizma i sporta").naziv())
                .isEqualTo("Ministarstvo turizma i sporta");
        assertThat(tijelo("MINISTARSTVO TURIZMA I SPORTA").naziv())
                .isEqualTo("MINISTARSTVO TURIZMA I SPORTA");
    }

    @Test
    void nazivTijela_prazanOstajePrazan() {
        assertThat(tijelo(null).naziv()).isNull();
        assertThat(tijelo("  ").naziv()).isEqualTo("  ");
        assertThat(new DocumentProperties(null, null, null, null, false, false, null).tijelo().naziv()).isNull();
    }

    /**
     * Propis o nadležnosti po vrsti akta iz stvarnog application.properties (MINTS, studeni 2026.):
     * ključevi sa crticom („prijedlog-suspenzije") moraju se vezati kao slug, a vrsta bez unosa
     * pada na zajednički propis tijela.
     */
    @Test
    void propisPoVrstiAkta_izApplicationProperties() throws java.io.IOException {
        var izvori = new org.springframework.core.env.MutablePropertySources();
        new org.springframework.boot.env.PropertiesPropertySourceLoader()
                .load("application", new org.springframework.core.io.ClassPathResource("application.properties"))
                .forEach(izvori::addLast);
        DocumentProperties props = new org.springframework.boot.context.properties.bind.Binder(
                org.springframework.boot.context.properties.source.ConfigurationPropertySources.from(izvori),
                new org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver(izvori))
                .bind("str.documents", DocumentProperties.class).get();

        String clanak46 = "članka 46. stavka 3. Zakona o ugostiteljskoj djelatnosti (Narodne novine, broj ___)";
        String clanak48 = "članka 48. stavka 2. Zakona o ugostiteljskoj djelatnosti (Narodne novine, broj ___)";
        assertThat(props.propisZa(StrDocumentType.DODJELA)).isEqualTo(clanak46);
        for (StrDocumentType type : new StrDocumentType[]{StrDocumentType.OPOZIV,
                StrDocumentType.PRIJEDLOG_SUSPENZIJE, StrDocumentType.OBUSTAVA_SUSPENZIJE,
                StrDocumentType.SUSPENZIJA, StrDocumentType.POVLACENJE}) {
            assertThat(props.propisZa(type)).as(type.slug()).isEqualTo(clanak48);
        }
        assertThat(props.propisZa(StrDocumentType.REAKTIVACIJA))
                .isEqualTo("članka 46. Zakona o ugostiteljskoj djelatnosti (Narodne novine, broj ___)");
    }
}
