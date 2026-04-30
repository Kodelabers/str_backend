package com.str.backend.validation;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class PipelineResult {

    public enum Outcome { PASSED, REJECTED }

    private Outcome outcome;
    private String step;
    private String detail;

    public static PipelineResult passed() {
        return new PipelineResult(Outcome.PASSED, "ALL", "all GO steps passed");
    }

    public static PipelineResult rejected(String step, String detail) {
        return new PipelineResult(Outcome.REJECTED, step, detail);
    }
}
