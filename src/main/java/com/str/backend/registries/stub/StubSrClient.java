package com.str.backend.registries.stub;

import com.str.backend.core.sr.CoreSrPravnaOsobaRepository;
import com.str.backend.registries.SrClient;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Component
public class StubSrClient implements SrClient {

    private final CoreSrPravnaOsobaRepository repository;

    public StubSrClient(CoreSrPravnaOsobaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<PravnaOsoba> dohvatiPravnuOsobu(String oib) {
        if (oib == null || oib.isBlank()) {
            return Optional.empty();
        }
        return repository.findById(oib)
                .map(p -> new PravnaOsoba(
                        p.getOib(),
                        p.getNaziv(),
                        p.getSjediste(),
                        parseZastupnici(p.getZastupnici())));
    }

    private List<String> parseZastupnici(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(";"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
