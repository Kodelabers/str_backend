package com.str.backend.registries.stub;

import com.str.backend.core.gis.CoreGisParcelaRepository;
import com.str.backend.registries.GisClient;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class StubGisClient implements GisClient {

    private final CoreGisParcelaRepository repository;

    public StubGisClient(CoreGisParcelaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<Parcela> dohvatiParcelu(String katastarskaOpcina, String brojCestice) {
        if (katastarskaOpcina == null || brojCestice == null) {
            return Optional.empty();
        }
        return repository.findByKatastarskaOpcinaAndBrojCestice(katastarskaOpcina, brojCestice)
                .map(p -> new Parcela(p.getKatastarskaOpcina(), p.getBrojCestice(),
                        p.getPovrsinaM2(), p.getNamjena(), p.getLegalanObjekt()));
    }
}
