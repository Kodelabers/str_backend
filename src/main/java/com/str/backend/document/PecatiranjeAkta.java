package com.str.backend.document;

/**
 * Hoće li akt tijela nakon rendera dobiti kvalificirani e-pečat — o tome ovisi ispisuje li renderer
 * blok e-pečata ili izjavu o valjanosti bez pečata.
 *
 * <p>Sučelje živi ovdje, a odgovor zna eGOP tok ({@code egop.EgopPecatiranjeAkta}): pečatira se
 * samo akt koji se urudžbira, a {@code egop} već ovisi o {@code document}. Ručni akt županije (T16)
 * ovdje se ne pita — renderer ga po {@link ZupContextFactory#BEZ_EPECATA} sam isključuje.
 */
@FunctionalInterface
public interface PecatiranjeAkta {

    boolean bitCePecatiran(StrDocumentType type);
}
