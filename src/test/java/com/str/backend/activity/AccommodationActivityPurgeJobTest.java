package com.str.backend.activity;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccommodationActivityPurgeJobTest {

    @Test
    void purgeExpired_delegatesToService() {
        AccommodationActivityService service = mock(AccommodationActivityService.class);
        when(service.purgeExpired()).thenReturn(3);

        new AccommodationActivityPurgeJob(service).purgeExpired();

        verify(service).purgeExpired();
    }
}
