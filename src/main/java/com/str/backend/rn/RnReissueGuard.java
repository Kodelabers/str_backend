package com.str.backend.rn;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Je li za objekt povučenog broja u međuvremenu izdan drugi važeći (nekonačan) broj (T12). Tada se
 * prigovor na povlačenje ne smije uvažiti — dva važeća broja za isti objekt ne smiju postojati — pa ga
 * se odbija već pri predaji ({@code SuspensionObjectionService#submit}), pri uvažavanju
 * ({@code RnService#reinstate}), a iznajmljivačev popis po njemu skriva gumb ({@code noviBrojIzdan}).
 *
 * <p>Isti objekt — koji blokira — prepoznaje se na dva načina:
 * <ul>
 *   <li>isti eTurizam objekt ({@code accommodation.facility_id});</li>
 *   <li>lanac ponovnih zahtjeva (T15, {@code accommodation.previous_rn}), i preko konačnih karika.</li>
 * </ul>
 *
 * <p>Ista adresa istog iznajmljivača ({@link RnRepository#findStandingRnsOfSameLessorAtSameAddress}) samo
 * upozorava (odluka korisnika, T12): adresa ne razlikuje jedinice u istoj zgradi, pa službenik u popisu
 * prigovora dobiva {@link #brojeviNaIstojAdresi} i sam odlučuje.
 */
@Component
public class RnReissueGuard {

    /** Najviše brojeva u lancu ponovnih zahtjeva — zaštita od petlje u ručno izmijenjenim podacima. */
    private static final int MAX_REISSUE_CHAIN = 50;

    private final RnRepository rnRepository;

    public RnReissueGuard(RnRepository rnRepository) {
        this.rnRepository = rnRepository;
    }

    /**
     * {@code true} kad za isti objekt broja {@code rn} (eTurizam objekt ili lanac ponovnih zahtjeva) postoji
     * drugi nekonačan broj. Nepoznat broj je {@code false}. Adresa ovdje ne sudjeluje.
     */
    @Transactional(readOnly = true)
    public boolean noviBrojIzdan(String rn) {
        return rnRepository.findById(rn).map(this::noviBrojIzdan).orElse(false);
    }

    @Transactional(readOnly = true)
    public boolean noviBrojIzdan(RnEntity e) {
        return istiEturizamObjekt(e) || lanacPonovnihZahtjeva(e);
    }

    private boolean istiEturizamObjekt(RnEntity e) {
        String facilityId = rnRepository.findFacilityIdByRn(e.getRn()).map(String::strip).orElse("");
        return !facilityId.isEmpty() && rnRepository.findRnsByFacilityIds(List.of(facilityId)).stream()
                .anyMatch(row -> !e.getRn().equals(row.getRn()));
    }

    private boolean lanacPonovnihZahtjeva(RnEntity e) {
        Set<String> vidjeni = new HashSet<>();
        Deque<String> red = new ArrayDeque<>(List.of(e.getRn()));
        while (!red.isEmpty() && vidjeni.size() < MAX_REISSUE_CHAIN) {
            String prethodni = red.poll();
            if (!vidjeni.add(prethodni)) {
                continue;
            }
            for (RnEntity novi : rnRepository.findReissuesOf(prethodni)) {
                if (!novi.getStatus().isTerminal()) {
                    return true;
                }
                red.add(novi.getRn());
            }
        }
        return false;
    }

    /**
     * Važeći (nekonačni) brojevi istog iznajmljivača na istoj adresi — upozorenje službeniku uz prigovor na
     * povučen broj, ne blokada (T12).
     */
    @Transactional(readOnly = true)
    public List<String> brojeviNaIstojAdresi(String rn) {
        return rnRepository.findStandingRnsOfSameLessorAtSameAddress(rn);
    }
}
