package com.str.backend.attachment;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PrilogCleanupJobTest {

    @Test
    void purgeStaged_deletesUnboundOlderThanTtl() {
        PrilogRepository repository = mock(PrilogRepository.class);
        Instant before = Instant.now();

        new PrilogCleanupJob(repository, Duration.ofHours(24)).purgeStaged();

        ArgumentCaptor<Instant> granica = ArgumentCaptor.forClass(Instant.class);
        verify(repository).deleteStagedBefore(granica.capture());
        assertThat(granica.getValue()).isBetween(before.minus(Duration.ofHours(24)),
                Instant.now().minus(Duration.ofHours(24)));
    }
}
