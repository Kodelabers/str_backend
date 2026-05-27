package com.str.backend.str;

import com.str.backend.address.HouseNumberRepository;
import com.str.backend.exception.ResourceNotFoundException;
import com.str.backend.lessor.LessorEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class StrLessorLookupService {

    private final StrSubjectRepository subjectRepository;
    private final StrSubjectVersionRepository subjectVersionRepository;
    private final StrSubjectAddressRepository subjectAddressRepository;
    private final HouseNumberRepository houseNumberRepository;

    public StrLessorLookupService(StrSubjectRepository subjectRepository,
                                  StrSubjectVersionRepository subjectVersionRepository,
                                  StrSubjectAddressRepository subjectAddressRepository,
                                  HouseNumberRepository houseNumberRepository) {
        this.subjectRepository = subjectRepository;
        this.subjectVersionRepository = subjectVersionRepository;
        this.subjectAddressRepository = subjectAddressRepository;
        this.houseNumberRepository = houseNumberRepository;
    }

    public LessorEntity resolveLessor(String oib) {
        StrSubjectEntity subject = subjectRepository.findFirstByJipsAndActiveTrue(oib)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Iznajmljivač nije registriran u sustavu (str.subject)"));

        StrSubjectVersionEntity version = subjectVersionRepository
                .findFirstBySubjectIdAndActiveTrueAndHistoricalFalseOrderByIdDesc(subject.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Aktivna verzija iznajmljivača (subject_version) nije pronađena"));

        HouseNumberRepository.LessorAddressProjection addr = subjectAddressRepository
                .findFirstBySubjectVersionIdAndActiveTrueOrderByIdDesc(version.getId())
                .flatMap(sa -> houseNumberRepository.resolveFullAddress(sa.getAddressId()))
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Adresa iznajmljivača nije pronađena"));

        LessorEntity lessor = LessorEntity.create(
                nullSafe(version.getFirstName(), "N/A"),
                nullSafe(version.getLastName(), "N/A"),
                nullSafe(addr.getStreet(), ""),
                nullSafe(addr.getStreetNumber(), ""),
                nullSafe(addr.getSettlement(), ""),
                nullSafe(addr.getCounty(), ""),
                null);
        lessor.setLessorOib(version.getPin() != null ? version.getPin() : oib);
        if (version.getName() != null && !version.getName().isBlank()) {
            lessor.setLegalEntityName(version.getName());
        }
        return lessor;
    }

    private static String nullSafe(String value, String fallback) {
        return (value != null && !value.isBlank()) ? value : fallback;
    }
}
