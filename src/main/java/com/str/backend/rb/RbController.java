package com.str.backend.rb;

import com.str.backend.domain.RbTrigger;
import com.str.backend.rb.dto.RbResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/rb")
public class RbController {

    private final RbService service;
    private final RbMapper mapper;

    public RbController(RbService service, RbMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    /** STR-1.5: prikaz nevažećih RB (SUSPENDIRAN + POVUCEN). */
    @GetMapping("/nevazeci")
    public List<RbResponse> nevazeci() {
        return mapper.toResponseList(service.nevazeci());
    }

    @GetMapping("/{rb}")
    public RbResponse get(@PathVariable String rb) {
        return mapper.toResponse(service.dohvati(rb));
    }

    @PostMapping("/{rb}/suspend")
    public RbResponse suspend(@PathVariable String rb, @RequestParam RbTrigger razlog) {
        return mapper.toResponse(service.suspend(rb, razlog));
    }

    @PostMapping("/{rb}/reactivate")
    public RbResponse reactivate(@PathVariable String rb) {
        return mapper.toResponse(service.reactivate(rb));
    }

    @PostMapping("/{rb}/withdraw")
    public RbResponse withdraw(@PathVariable String rb) {
        return mapper.toResponse(service.withdraw(rb));
    }
}
