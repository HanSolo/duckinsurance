package eu.hansolo.duckinsurance.service;

import eu.hansolo.duckinsurance.audit.Audited;
import eu.hansolo.duckinsurance.domain.Duck;
import eu.hansolo.duckinsurance.domain.DomainException;
import eu.hansolo.duckinsurance.repo.Ducks;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

@ApplicationScoped
@Transactional
public class DuckService {

    @Inject
    private Ducks           ducks;
    @Inject
    private CustomerService customers;


    /** All ducks, or only the ducks of one owner. */
    public List<Duck> list(Long ownerId) {
        List<Duck> result;
        if (ownerId == null) {
            try (Stream<Duck> all = ducks.findAll()) { result = all.toList(); }
        } else {
            result = ducks.byOwner(ownerId);
        }
        return result.stream().sorted(Comparator.comparing(Duck::getName)).toList();
    }

    public Duck get(long id) {
        return ducks.findById(id).orElseThrow(() -> DomainException.notFound("Duck " + id));
    }

    @Audited
    public Duck create(Duck duck) {
        customers.get(duck.getOwnerId());
        duck.setId(null);
        return ducks.save(duck);
    }
}
