package eu.hansolo.duckinsurance.repo;

import eu.hansolo.duckinsurance.domain.Vet;
import jakarta.data.repository.CrudRepository;
import jakarta.data.repository.Repository;

@Repository
public interface Vets extends CrudRepository<Vet, Long> {
}
