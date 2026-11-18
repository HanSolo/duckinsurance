package eu.hansolo.duckinsurance.service;

import eu.hansolo.duckinsurance.audit.Audited;
import eu.hansolo.duckinsurance.domain.DomainException;
import eu.hansolo.duckinsurance.domain.Vet;
import eu.hansolo.duckinsurance.repo.Vets;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

@ApplicationScoped
@Transactional
public class VetService {

    @Inject
    private Vets vets;


    public List<Vet> list() {
        try (Stream<Vet> all = vets.findAll()) { return all.sorted(Comparator.comparing(Vet::getName)).toList(); }
    }

    public Vet get(long id) {
        return vets.findById(id).orElseThrow(() -> DomainException.notFound("Vet " + id));
    }

    @Audited
    public Vet create(Vet vet) {
        vet.setId(null);
        return vets.save(vet);
    }
}
