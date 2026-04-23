package com.str.backend.domain;

public enum ZahtjevStatus {
    INICIIRAN,
    U_VERIFIKACIJI,
    U_OBRADI,
    PRIHVACEN,
    ODBIJEN;

    public boolean canTransitionTo(ZahtjevStatus target, ZahtjevTrigger trigger) {
        return switch (this) {
            case INICIIRAN -> switch (trigger) {
                case SUBMIT -> target == U_OBRADI;
                case STRANAC_UPLOAD -> target == U_VERIFIKACIJI;
                default -> false;
            };
            case U_VERIFIKACIJI -> trigger == ZahtjevTrigger.REFERENT_APPROVE && target == U_OBRADI;
            case U_OBRADI -> switch (trigger) {
                case VALIDATION_PASSED -> target == PRIHVACEN;
                case VALIDATION_REJECTED -> target == ODBIJEN;
                default -> false;
            };
            case PRIHVACEN, ODBIJEN -> false;
        };
    }

    public boolean isTerminal() {
        return this == PRIHVACEN || this == ODBIJEN;
    }
}
