package com.str.backend.lessor;

/**
 * Provjera adrese podnositelja upisane na obrascu prije nego postane adresa iznajmljivača:
 * lanac adresnog registra i nazivi iz registra. Sučelje, a ne izravna ovisnost o adresnom modulu,
 * da testovi {@link SubjectProfileService} ne moraju graditi registar.
 *
 * @see RegistryEnteredAddressVerifier
 */
@FunctionalInterface
public interface EnteredAddressVerifier {

    /**
     * @return adresa s nazivima iz registra
     * @throws com.str.backend.exception.AddressHierarchyException adresa nije povezan lanac registra
     *                                                             ili joj nedostaje razina (400)
     */
    EnteredAddress verify(EnteredAddress entered);
}
