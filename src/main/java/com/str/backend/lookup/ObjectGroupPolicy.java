package com.str.backend.lookup;

import com.str.backend.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Pravilo o tome što koja vrsta subjekta smije prijaviti (T13), primijenjeno na šifrarnik vrsta.
 * Pravilo je u tablicama ({@link ObjectGroupEntity}); ovdje je samo njegovo čitanje, da izbornik,
 * popis objekata, provjera eTurizam objekta i predaja zahtjeva gledaju isto.
 *
 * <p>Fizička osoba i OPG biraju vrstu ({@code FS_*}), a ostali poslovni subjekti skupinu
 * ({@code FT_*}) — za njih je redak šifrarnika skupina sama. Zato se eTurizam objekt razrješava
 * prvo po vrsti, pa po skupini ({@link #resolveFacilityType}).
 */
@Service
public class ObjectGroupPolicy {

    /** Oznaka skupine u RB-u kad vrsta nije poznata — tako je izdan svaki RB prije T13. */
    static final int DEFAULT_RB_GROUP_CODE = 0;

    private final ObjectGroupRepository objectGroupRepository;
    private final AccommodationTypeRepository accommodationTypeRepository;

    public ObjectGroupPolicy(ObjectGroupRepository objectGroupRepository,
                             AccommodationTypeRepository accommodationTypeRepository) {
        this.objectGroupRepository = objectGroupRepository;
        this.accommodationTypeRepository = accommodationTypeRepository;
    }

    /** Skupine objekata koje subjekt te vrste smije prijaviti. */
    @Transactional(readOnly = true)
    public List<String> allowedGroups(SubjectCategory category) {
        return objectGroupRepository.findGroupCodes(category);
    }

    /** Stavke izbornika (vrste ili skupine, s pravom na RB) koje subjekt te vrste smije prijaviti. */
    @Transactional(readOnly = true)
    public List<AccommodationTypeEntity> allowedTypes(SubjectCategory category) {
        List<String> groups = allowedGroups(category);
        return groups.isEmpty() ? List.of()
                : accommodationTypeRepository.findAllByRegistrationNumberAllowedTrueAndGroupIn(groups);
    }

    /**
     * Šifre koje subjekt te vrste smije imati — filtar popisa objekata: {@code FS_*} vrste za fizičku
     * osobu i OPG, {@code FT_*} skupine za ostale poslovne subjekte. Popis ih uspoređuje i s vrstom
     * i sa skupinom eTurizam objekta.
     */
    @Transactional(readOnly = true)
    public List<String> allowedTypeCodes(SubjectCategory category) {
        List<String> groups = allowedGroups(category);
        return groups.isEmpty() ? List.of() : accommodationTypeRepository.findCodesByGroupIn(groups);
    }

    /**
     * Redak šifrarnika koji odgovara eTurizam objektu za subjekt te vrste: prvo po vrsti
     * ({@code FS_*} — fizička osoba, OPG), pa po skupini ({@code FT_*} — ostali poslovni subjekti).
     * Prazno kad subjekt objekt te vrste ne smije prijaviti.
     *
     * <p>Redoslijed je bitan: ista vrsta {@code FS_SOBA} je fizičkoj osobi domaćinstvo, a tvrtki
     * dio skupine {@code FT_OST_KAT}, pa tvrtka dobiva redak skupine, a ne domaćinstva.
     */
    @Transactional(readOnly = true)
    public Optional<AccommodationTypeEntity> resolveFacilityType(String subtypeCode, String typeCode,
                                                                SubjectCategory category) {
        List<String> groups = allowedGroups(category);
        return Stream.of(subtypeCode, typeCode)
                .filter(code -> code != null && !code.isBlank())
                .map(code -> accommodationTypeRepository.findByCodeIgnoreCase(code.trim()))
                .flatMap(Optional::stream)
                .filter(type -> groups.contains(type.getGroup()))
                .findFirst();
    }

    /**
     * Smije li subjekt te vrste za eTurizam objekt dobiti RB: objekt se mora razriješiti u redak
     * šifrarnika ({@link #resolveFacilityType}) s pravom na RB. Kad eTurizam ne zna ni vrstu ni
     * skupinu, zabrana se ne može utemeljiti, pa RB nije blokiran — postojeći objekt mora moći
     * dobiti RB, a {@code RnService.issue()} provjerava vrstu čim je poznata.
     *
     * @param subtypeCode vrsta iz eTurizma, već očišćena od popunjivača (ili {@code null})
     * @param typeCode    skupina iz eTurizma, već očišćena od popunjivača (ili {@code null})
     */
    @Transactional(readOnly = true)
    public boolean isRegistrable(String subtypeCode, String typeCode, SubjectCategory category) {
        if ((subtypeCode == null || subtypeCode.isBlank()) && (typeCode == null || typeCode.isBlank())) {
            return true;
        }
        return resolveFacilityType(subtypeCode, typeCode, category)
                .map(AccommodationTypeEntity::isRegistrationNumberAllowed)
                .orElse(false);
    }

    /**
     * Vrsta objekta mora biti iz skupine koju subjekt smije prijaviti (npr. tvrtka ne smije objekt u
     * domaćinstvu). Bez vrste nema što provjeriti — nepoznatu vrstu odbija razrješavanje zahtjeva,
     * a vrstu bez prava na RB {@code RnService.issue()}.
     */
    @Transactional(readOnly = true)
    public void requireTypeAllowed(Long accommodationTypeId, SubjectCategory category) {
        if (accommodationTypeId == null) {
            return;
        }
        String group = accommodationTypeRepository.findById(accommodationTypeId)
                .map(AccommodationTypeEntity::getGroup)
                .orElse(null);
        if (group == null || !allowedGroups(category).contains(group)) {
            throw new BusinessException("error.accommodation.type.notAllowedForSubject");
        }
    }

    /** Skupina kojoj vrsta pripada; prazno kad vrsta nije zadana ili nije poznata. */
    @Transactional(readOnly = true)
    public Optional<ObjectGroupEntity> groupOfType(Long accommodationTypeId) {
        if (accommodationTypeId == null) {
            return Optional.empty();
        }
        return accommodationTypeRepository.findById(accommodationTypeId)
                .flatMap(type -> objectGroupRepository.findById(type.getGroup()));
    }

    /** Oznaka skupine za registracijski broj objekta te vrste. */
    @Transactional(readOnly = true)
    public int rbGroupCode(Long accommodationTypeId) {
        return groupOfType(accommodationTypeId)
                .map(group -> (int) group.getRbGroupCode())
                .orElse(DEFAULT_RB_GROUP_CODE);
    }
}
