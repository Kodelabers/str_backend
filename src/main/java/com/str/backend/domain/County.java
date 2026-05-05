package com.str.backend.domain;

public enum County {

    ZAGREBACKA(2),
    KRAPINSKO_ZAGORSKA(3),
    SISACKO_MOSLAVACKA(4),
    KARLOVACKA(5),
    VARAZDINSKA(6),
    KOPRIVNICKO_KRIZEVACKA(7),
    BJELOVARSKO_BILOGORSKA(8),
    PRIMORSKO_GORANSKA(9),
    LICKO_SENJSKA(10),
    VIROVITICKO_PODRAVSKA(11),
    POZESKO_SLAVONSKA(12),
    BRODSKO_POSAVSKA(13),
    ZADARSKA(14),
    OSJECKO_BARANJSKA(15),
    SIBENSKO_KNINSKA(16),
    VUKOVARSKO_SRIJEMSKA(17),
    SPLITSKO_DALMATINSKA(18),
    ISTARSKA(19),
    DUBROVACKO_NERETVANSKA(20),
    MEDIMURSKA(21),
    GRAD_ZAGREB(22);

    private final long organizationId;

    County(long organizationId) {
        this.organizationId = organizationId;
    }

    public long getOrganizationId() {
        return organizationId;
    }
}
