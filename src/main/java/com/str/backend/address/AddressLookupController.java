package com.str.backend.address;

import com.str.backend.address.dto.CountyResponse;
import com.str.backend.address.dto.HouseNumberResponse;
import com.str.backend.address.dto.MunicipalityResponse;
import com.str.backend.address.dto.SettlementResponse;
import com.str.backend.address.dto.StreetResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/address")
public class AddressLookupController {

    private final AddressLookupService service;

    public AddressLookupController(AddressLookupService service) {
        this.service = service;
    }

    @GetMapping("/counties")
    public List<CountyResponse> getCounties(@RequestParam(required = false) String q) {
        return service.findCounties(q);
    }

    @GetMapping("/municipalities")
    public List<MunicipalityResponse> getMunicipalities(@RequestParam Long zupanijaId,
                                                        @RequestParam(required = false) String q) {
        return service.findMunicipalities(zupanijaId, q);
    }

    @GetMapping("/settlements")
    public List<SettlementResponse> getSettlements(@RequestParam Long opcinaId,
                                                   @RequestParam(required = false) String q) {
        return service.findSettlements(opcinaId, q);
    }

    @GetMapping("/streets")
    public List<StreetResponse> getStreets(@RequestParam Long naseljeId,
                                           @RequestParam(required = false) String q) {
        return service.findStreets(naseljeId, q);
    }

    @GetMapping("/house-numbers")
    public List<HouseNumberResponse> getHouseNumbers(@RequestParam Long ulicaId,
                                                     @RequestParam(required = false) String q) {
        return service.findHouseNumbers(ulicaId, q);
    }
}
