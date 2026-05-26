package com.str.backend.rn;

import com.str.backend.domain.RegistrationNumber;
import com.str.backend.rn.dto.VerifyResponse;
import jakarta.validation.constraints.Pattern;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/verify")
public class VerifyController {

    private final RnRepository rnRepository;

    public VerifyController(RnRepository rnRepository) {
        this.rnRepository = rnRepository;
    }

    @GetMapping("/{rn}")
    @Transactional(readOnly = true)
    public VerifyResponse verify(
            @PathVariable @Pattern(regexp = RegistrationNumber.REGEXP) String rn) {
        boolean valid = rnRepository.findById(rn)
                .map(entity -> entity.getStatus().isPubliclyVisible())
                .orElse(false);
        return new VerifyResponse(valid);
    }
}
