package eu.hansolo.duckinsurance.repo;

import eu.hansolo.duckinsurance.domain.Policy;
import eu.hansolo.duckinsurance.domain.PolicyStatus;
import jakarta.data.repository.By;
import jakarta.data.repository.CrudRepository;
import jakarta.data.repository.Find;
import jakarta.data.repository.Repository;

import java.util.List;

@Repository
public interface Policies extends CrudRepository<Policy, Long> {

    @Find
    List<Policy> byCustomer(@By("customerId") Long customerId);

    @Find
    List<Policy> byStatus(@By("status") PolicyStatus status);
}
