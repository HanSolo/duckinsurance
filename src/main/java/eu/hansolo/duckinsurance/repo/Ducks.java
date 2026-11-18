package eu.hansolo.duckinsurance.repo;

import eu.hansolo.duckinsurance.domain.Duck;
import jakarta.data.repository.By;
import jakarta.data.repository.CrudRepository;
import jakarta.data.repository.Find;
import jakarta.data.repository.Repository;

import java.util.List;

@Repository
public interface Ducks extends CrudRepository<Duck, Long> {

    @Find
    List<Duck> byOwner(@By("ownerId") Long ownerId);
}
