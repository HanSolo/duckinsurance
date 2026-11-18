package eu.hansolo.duckinsurance.repo;

import eu.hansolo.duckinsurance.domain.Customer;
import jakarta.data.repository.CrudRepository;
import jakarta.data.repository.Param;
import jakarta.data.repository.Query;
import jakarta.data.repository.Repository;

import java.util.List;

/** Jakarta Data 1.0: the container generates the implementation. */
@Repository
public interface Customers extends CrudRepository<Customer, Long> {

    @Query("where lower(lastName) like :term or lower(firstName) like :term or lower(email) like :term")
    List<Customer> search(@Param("term") String term);
}
