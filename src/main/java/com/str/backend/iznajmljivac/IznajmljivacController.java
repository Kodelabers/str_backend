package com.str.backend.iznajmljivac;

import com.str.backend.iznajmljivac.dto.CreateIznajmljivacRequest;
import com.str.backend.iznajmljivac.dto.IznajmljivacResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v2/iznajmljivaci")
public class IznajmljivacController {

    private final IznajmljivacService service;

    public IznajmljivacController(IznajmljivacService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<IznajmljivacResponse> register(@Valid @RequestBody CreateIznajmljivacRequest req) {
        IznajmljivacEntity e = service.register(req);
        return ResponseEntity.status(HttpStatus.CREATED).body(IznajmljivacResponse.from(e));
    }

    @GetMapping("/{id}")
    public IznajmljivacResponse get(@PathVariable UUID id) {
        return IznajmljivacResponse.from(service.dohvati(id));
    }
}
