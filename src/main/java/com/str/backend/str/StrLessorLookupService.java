package com.str.backend.str;

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
    private final StrAddressRepository addressRepository;

    public StrLessorLookupService(StrSubjectRepository subjectRepository,
                                  StrSubjectVersionRepository subjectVersionRepository,
                                  StrSubjectAddressRepository subjectAddressRepository,
                                  StrAddressRepository addressRepository) {
        this.subjectRepository = subjectRepository;
        this.subjectVersionRepository = subjectVersionRepository;
        this.subjectAddressRepository = subjectAddressRepository;
        this.addressRepository = addressRepository;
    }

    public LessorEntity resolveLessor(String oib) {
        StrSubjectEntity subject = subjectRepository.findFirstByJipsAndActiveTrue(oib)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Iznajmljivač nije registriran u sustavu (str.subject)"));

        StrSubjectVersionEntity version = subjectVersionRepository
                .findFirstBySubjectIdAndActiveTrueAndHistoricalFalseOrderByIdDesc(subject.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Aktivna verzija iznajmljivača (subject_version) nije pronađena"));

        StrAddressEntity address = subjectAddressRepository
                .findFirstBySubjectVersionIdAndActiveTrueOrderByIdDesc(version.getId())
                .flatMap(sa -> addressRepository.findById(sa.getAddressId()))
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Adresa iznajmljivača nije pronađena"));

        LessorEntity lessor = LessorEntity.create(
                nullSafe(version.getFirstName(), "N/A"),
                nullSafe(version.getLastName(), "N/A"),
                nullSafe(address.getStreet(), "N/A"),
                nullSafe(address.getHouseNumber(), "N/A"),
                nullSafe(address.getSettlement(), "N/A"),
                nullSafe(address.getCounty(), "N/A"),
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
