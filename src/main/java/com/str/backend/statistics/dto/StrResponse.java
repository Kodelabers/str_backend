package com.str.backend.statistics.dto;

import java.util.List;

public record StrResponse(
        StrTotalsDto totals,
        List<CountyStrDto> counties
) {
}
