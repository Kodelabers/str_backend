package com.str.backend.validation;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class PipelineRezultat {

    public enum Ishod { PROSAO, ODBIJEN }

    private Ishod ishod;
    private String step;
    private String detail;

    public static PipelineRezultat prosao() {
        return new PipelineRezultat(Ishod.PROSAO, "ALL", "all GO steps passed");
    }

    public static PipelineRezultat odbijen(String step, String detail) {
        return new PipelineRezultat(Ishod.ODBIJEN, step, detail);
    }
}
