package com.str.backend.statistics.dto;

import java.util.List;

public record BpsoResponse(
        BpsoTotalsDto totals,
        List<CountyBpsoDto> counties
) {
}
