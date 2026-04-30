package com.str.backend.rn;

import com.str.backend.domain.RnTrigger;
import com.str.backend.rn.dto.RnResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/rn")
public class RnController {

    private final RnService service;
    private final RnMapper mapper;

    public RnController(RnService service, RnMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    /** STR-1.5: display of inactive RNs (SUSPENDED + WITHDRAWN). */
    @GetMapping("/inactive")
    public List<RnResponse> inactive() {
        return mapper.toResponseList(service.inactive());
    }

    @GetMapping("/{rn}")
    public RnResponse get(@PathVariable String rn) {
        return mapper.toResponse(service.find(rn));
    }

    @PostMapping("/{rn}/suspend")
    public RnResponse suspend(@PathVariable String rn, @RequestParam RnTrigger reason) {
        return mapper.toResponse(service.suspend(rn, reason));
    }

    @PostMapping("/{rn}/reactivate")
    public RnResponse reactivate(@PathVariable String rn) {
        return mapper.toResponse(service.reactivate(rn));
    }

    @PostMapping("/{rn}/withdraw")
    public RnResponse withdraw(@PathVariable String rn) {
        return mapper.toResponse(service.withdraw(rn));
    }
}
