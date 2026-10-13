package com.str.backend.lessor;

import com.str.backend.address.AddressHierarchyValidator;
import com.str.backend.address.AddressHierarchyValidator.AddressSelection;
import com.str.backend.address.AddressHierarchyValidator.VerifiedAddress;
import org.springframework.stereotype.Component;

/**
 * {@link EnteredAddressVerifier} nad adresnim registrom ({@code rpj_dgu} + {@code eturizam_test}) —
 * v. {@link AddressHierarchyValidator#verifySubjectAddress}. Id-evi i MBS ostaju kakvi su stigli,
 * nazivi razina dolaze iz registra.
 */
@Component
class RegistryEnteredAddressVerifier implements EnteredAddressVerifier {

    private final AddressHierarchyValidator validator;

    RegistryEnteredAddressVerifier(AddressHierarchyValidator validator) {
        this.validator = validator;
    }

    @Override
    public EnteredAddress verify(EnteredAddress entered) {
        VerifiedAddress v = validator.verifySubjectAddress(new AddressSelection(
                entered.countyId(), entered.municipalityId(), entered.settlementId(),
                entered.streetId(), entered.houseNumberId(),
                entered.street(), entered.streetNumber(), entered.postalCode()));
        return new EnteredAddress(v.street(), v.houseNumber(), v.postalCode(), v.settlement(),
                v.municipality(), v.county(), entered.registrationNumber(),
                entered.countyId(), entered.municipalityId(), entered.settlementId(),
                entered.streetId(), entered.houseNumberId());
    }
}
