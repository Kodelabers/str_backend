package com.str.backend.rb;

import com.str.backend.domain.RbTrigger;
import com.str.backend.rb.dto.RbResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/rb")
public class RbController {

    private final RbService service;

    public RbController(RbService service) {
        this.service = service;
    }

    @GetMapping("/{rb}")
    public RbResponse get(@PathVariable String rb) {
        return RbResponse.from(service.dohvati(rb));
    }

    @PostMapping("/{rb}/suspend")
    public RbResponse suspend(@PathVariable String rb, @RequestParam RbTrigger razlog) {
        return RbResponse.from(service.suspend(rb, razlog));
    }

    @PostMapping("/{rb}/reactivate")
    public RbResponse reactivate(@PathVariable String rb) {
        return RbResponse.from(service.reactivate(rb));
    }

    @PostMapping("/{rb}/withdraw")
    public RbResponse withdraw(@PathVariable String rb) {
        return RbResponse.from(service.withdraw(rb));
    }
}
