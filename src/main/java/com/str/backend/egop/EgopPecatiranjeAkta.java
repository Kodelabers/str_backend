package com.str.backend.egop;

import com.str.backend.document.PecatiranjeAkta;
import com.str.backend.document.StrDocumentType;
import com.str.backend.eseal.PdfSealer;
import org.springframework.stereotype.Component;

/**
 * Pečatira li {@code EgopFilingService.sealOnce} akt ove vrste: eSeal je uključen i vrsta se
 * urudžbira. Vrsta s popisa {@code str.egop.akti-bez-sifre} ne ide u eGOP pa ni na pečat, a stranci
 * se dostavlja snimak — taj mora nositi izjavu o valjanosti bez pečata.
 *
 * <p>Uključenost se čita iz {@link PdfSealer}, a ne iz {@code str.eseal.enabled}: isti izvor kao
 * {@code sealOnce}, pa izjava i pečat ne mogu razići ({@code ESEAL_ENABLED=yes} veže se kao
 * {@code true}, a klijent se ne digne).
 */
@Component
public class EgopPecatiranjeAkta implements PecatiranjeAkta {

    private final PdfSealer sealer;
    private final EgopAktiBezSifre bezSifre;

    public EgopPecatiranjeAkta(PdfSealer sealer, EgopAktiBezSifre bezSifre) {
        this.sealer = sealer;
        this.bezSifre = bezSifre;
    }

    @Override
    public boolean bitCePecatiran(StrDocumentType type) {
        return sealer.isEnabled() && bezSifre.urudzbiv(type);
    }
}
