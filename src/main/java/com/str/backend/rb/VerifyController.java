package com.str.backend.rb;

import com.str.backend.exception.ResourceNotFoundException;
import com.str.backend.rb.dto.VerifyResponse;
import com.str.backend.sso.SsoEntity;
import com.str.backend.sso.SsoRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/verify")
public class VerifyController {

    private final RbRepository rbRepository;
    private final SsoRepository ssoRepository;
    private final VerifyMapper mapper;

    public VerifyController(RbRepository rbRepository, SsoRepository ssoRepository, VerifyMapper mapper) {
        this.rbRepository = rbRepository;
        this.ssoRepository = ssoRepository;
        this.mapper = mapper;
    }

    @GetMapping("/{rb}")
    @Transactional(readOnly = true)
    public VerifyResponse verify(@PathVariable String rb) {
        RbEntity entity = rbRepository.findById(rb)
                .orElseThrow(() -> new ResourceNotFoundException("rb not found: " + rb));
        SsoEntity sso = ssoRepository.findById(entity.getIdSso())
                .orElseThrow(() -> new ResourceNotFoundException("sso not found: " + entity.getIdSso()));
        return mapper.toResponse(entity, sso);
    }
}
