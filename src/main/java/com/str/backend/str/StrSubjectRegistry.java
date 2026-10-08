package com.str.backend.str;

import com.str.backend.address.HouseNumberRepository;
import com.str.backend.address.HouseNumberRepository.LessorAddressProjection;
import com.str.backend.address.PostalCodes;
import com.str.backend.lessor.RegistrySubject;
import com.str.backend.lessor.SubjectDataSource;
import com.str.backend.lessor.SubjectRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Podaci o subjektu iz eTurizma ({@code str.subject*}) — <b>privremeni</b> izvor dok se za
 * fizičku osobu ne uključi OIB sustav. Isto ponašanje
 * kao raniji {@code StrLessorLookupService}, samo iza {@link SubjectRegistry} porta, pa se na OIB
 * sustav prelazi zastavicom {@code app.oib-registry.enabled=true}, bez izmjene poziva.
 *
 * <p>Na local/mock profilu {@code str.subject*} je mockiran (changeset 101), pa lokalni tok
 * radi nad istim testnim iznajmljivačima kao i prije.
 *
 * <p>Adresa nije obvezna: na CDU {@code str.subject_address.address_id} ne pogađa uvijek
 * {@code eturizam_test.ar_address}. Bez nje subjekt se vraća s praznom adresom — utječe na PDF i
 * GO-1 (status domaćina), ali ne blokira dodjelu RB-a.
 *
 * <p>Uz razriješenu adresu idu i općina ({@code gradovi_i_opcine.jls_ime}) i poštanski broj.
 * Broj se ne veže na kućni broj (shema ga nema), nego na naselje i županiju; kad naselje ima
 * više brojeva ili ga ima samo istoimeno naselje druge županije, šalje se {@code null} — v.
 * {@link PostalCodes#single}.
 */
@Component
@ConditionalOnProperty(name = "app.oib-registry.enabled", havingValue = "false", matchIfMissing = true)
@Transactional(readOnly = true)
public class StrSubjectRegistry implements SubjectRegistry {

    private final StrSubjectRepository subjectRepository;
    private final StrSubjectVersionRepository subjectVersionRepository;
    private final StrSubjectAddressRepository subjectAddressRepository;
    private final HouseNumberRepository houseNumberRepository;

    public StrSubjectRegistry(StrSubjectRepository subjectRepository,
                              StrSubjectVersionRepository subjectVersionRepository,
                              StrSubjectAddressRepository subjectAddressRepository,
                              HouseNumberRepository houseNumberRepository) {
        this.subjectRepository = subjectRepository;
        this.subjectVersionRepository = subjectVersionRepository;
        this.subjectAddressRepository = subjectAddressRepository;
        this.houseNumberRepository = houseNumberRepository;
    }

    @Override
    public Optional<RegistrySubject> findByOib(String oib) {
        return subjectRepository.findFirstByJipsAndActiveTrue(oib)
                .flatMap(subject -> subjectVersionRepository
                        .findFirstBySubjectIdAndActiveTrueAndHistoricalFalseOrderByIdDesc(subject.getId()))
                .map(version -> {
                    Optional<Long> addressId = subjectAddressRepository
                            .findFirstBySubjectVersionIdAndActiveTrueOrderByIdDesc(version.getId())
                            .map(StrSubjectAddressEntity::getAddressId);
                    Optional<LessorAddressProjection> address =
                            addressId.flatMap(houseNumberRepository::resolveFullAddress);
                    // Broj samo uz razriješenu adresu; više brojeva u naselju → null (v. PostalCodes)
                    String postalCode = address.isPresent()
                            ? PostalCodes.single(houseNumberRepository.findPostalCandidates(addressId.get()))
                            : null;
                    return new RegistrySubject(
                            version.getPin() != null ? version.getPin() : oib,
                            version.getFirstName(),
                            version.getLastName(),
                            version.getName(),
                            address.map(LessorAddressProjection::getStreet).orElse(null),
                            address.map(LessorAddressProjection::getStreetNumber).orElse(null),
                            address.map(LessorAddressProjection::getSettlement).orElse(null),
                            postalCode,
                            address.map(LessorAddressProjection::getMunicipality).orElse(null),
                            address.map(LessorAddressProjection::getCounty).orElse(null),
                            SubjectDataSource.STR_SUBJEKT);
                });
    }
}
