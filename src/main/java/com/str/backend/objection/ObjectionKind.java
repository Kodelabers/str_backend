package com.str.backend.objection;

import com.str.backend.domain.RnStatus;

import java.util.Arrays;
import java.util.List;

/**
 * Vrsta ulaznog pismena na popisu očitovanja i prigovora (MINTS 17.11., točka 1). Zasebnog stupca nema:
 * vrsta je status broja pri predaji ({@code suspension_objection.rn_status}), isto pravilo kao
 * {@link com.str.backend.document.StrDocumentType#podnesakNaAkt}. Na prijedlog se iznajmljivač očituje, a na
 * donesenu mjeru prigovara.
 */
public enum ObjectionKind {
    OCITOVANJE_SUSPENZIJE(RnStatus.SUSPENSION_PROPOSED),
    PRIGOVOR_SUSPENZIJE(RnStatus.SUSPENDED),
    OCITOVANJE_POVLACENJA(RnStatus.WITHDRAWAL_PROPOSED),
    PRIGOVOR_POVLACENJA(RnStatus.WITHDRAWN);

    private final RnStatus rnStatus;

    ObjectionKind(RnStatus rnStatus) {
        this.rnStatus = rnStatus;
    }

    /** Status broja pri predaji koji daje ovu vrstu. */
    public RnStatus rnStatus() {
        return rnStatus;
    }

    /** Statusi broja pri predaji za filtar vrste; bez filtra sve četiri (svaki podnesak ima jednu od njih). */
    public static List<RnStatus> rnStatuses(ObjectionKind kind) {
        return kind != null
                ? List.of(kind.rnStatus)
                : Arrays.stream(values()).map(ObjectionKind::rnStatus).toList();
    }

    /** Vrsta podneska predanog uz status broja; {@code null} za status uz koji se podnesak ne predaje. */
    public static ObjectionKind of(RnStatus rnStatus) {
        return Arrays.stream(values()).filter(k -> k.rnStatus == rnStatus).findFirst().orElse(null);
    }
}
