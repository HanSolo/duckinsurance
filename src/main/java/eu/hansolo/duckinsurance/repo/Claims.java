package eu.hansolo.duckinsurance.repo;

import eu.hansolo.duckinsurance.domain.Claim;
import eu.hansolo.duckinsurance.domain.ClaimStatus;
import jakarta.data.repository.By;
import jakarta.data.repository.CrudRepository;
import jakarta.data.repository.Find;
import jakarta.data.repository.Repository;

import java.util.List;

@Repository
public interface Claims extends CrudRepository<Claim, Long> {

    @Find
    List<Claim> byStatus(@By("status") ClaimStatus status);

    @Find
    List<Claim> byPolicy(@By("policyId") Long policyId);

    long countByStatus(ClaimStatus status);
}
