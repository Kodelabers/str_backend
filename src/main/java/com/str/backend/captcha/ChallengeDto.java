package com.str.backend.captcha;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ChallengeDto(
        String algorithm,
        String challenge,
        @JsonProperty("maxnumber") int maxNumber,
        String salt,
        String signature
) {
}
