package com.str.backend.validation;

public record PipelineRezultat(Ishod ishod, String step, String detail) {

    public enum Ishod { PROSAO, ODBIJEN }

    public static PipelineRezultat prosao() {
        return new PipelineRezultat(Ishod.PROSAO, "ALL", "all GO steps passed");
    }

    public static PipelineRezultat odbijen(String step, String detail) {
        return new PipelineRezultat(Ishod.ODBIJEN, step, detail);
    }
}
