package com.str.backend.config;

import liquibase.changelog.ChangeLogParameters;
import liquibase.changelog.DatabaseChangeLog;
import liquibase.parser.ChangeLogParser;
import liquibase.parser.ChangeLogParserFactory;
import liquibase.resource.ClassLoaderResourceAccessor;
import liquibase.resource.ResourceAccessor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Master changelog i svi uključeni changesetovi parsiraju se kao pri startu aplikacije, uz XSD
 * validaciju. Testni profil ima Liquibase isključen, pa bez ovoga neispravan changelog prolazi
 * sve testove i otkrije se tek na serveru — kad se backend ne digne (CDU, 16.10.2026: zalutali
 * znak između elemenata, sintaktički ispravan XML koji odbija tek shema).
 */
class ChangelogMasterParseTest {

    private static final String MASTER = "db/changelog/db.changelog-master.xml";

    @Test
    void masterChangelog_parsesWithSchemaValidation() throws Exception {
        ResourceAccessor resources = new ClassLoaderResourceAccessor(getClass().getClassLoader());
        ChangeLogParser parser = ChangeLogParserFactory.getInstance().getParser(MASTER, resources);

        DatabaseChangeLog changeLog = parser.parse(MASTER, new ChangeLogParameters(), resources);

        assertThat(changeLog.getChangeSets()).isNotEmpty();
    }
}
