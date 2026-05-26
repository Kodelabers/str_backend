package com.str.backend.statistics;

import com.str.backend.statistics.dto.BpsoResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/statistics")
public class StatisticsController {

    private final StatisticsService service;

    public StatisticsController(StatisticsService service) {
        this.service = service;
    }

    /** Wireframe §11: BPSO dashboard — RB counts per county. */
    @GetMapping("/bpso")
    public BpsoResponse bpso() {
        return service.bpso();
    }
}
