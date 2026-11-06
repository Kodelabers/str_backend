package com.str.backend.sdep;

import com.str.backend.activity.dto.SdepIngestRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * Predaja aktivnosti preko integracijskog API-ja. Razlika prema {@link SdepIngestRequest}: platforma
 * se ne zadaje ID-jem STR-a nego nazivom iz SDEP-a, pa SDEP više ne treba popis platformi STR-a.
 * Unosi su isti. Polja koja STR još ne čita (npr. {@code sdepPlatformId}, {@code guests},
 * {@code sdepActivityId}) Jackson preskače.
 */
@Data
public class SdepActivitiesRequest {

    @NotBlank
    private String platformName;

    @NotEmpty
    @Valid
    private List<SdepIngestRequest.Entry> entries;
}
