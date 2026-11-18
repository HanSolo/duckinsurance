package eu.hansolo.duckinsurance.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
public class Policy {

    @Id
    @SequenceGenerator(name = "policy_seq", sequenceName = "policy_seq", allocationSize = 1)
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "policy_seq")
    private Long         id;
    /** Plain foreign key keeps the demo simple (no lazy-loading surprises in JSON or Faces). */
    private Long         customerId;
    /** The insured duck. The customer is the duck's owner. */
    private Long         duckId;
    @Enumerated(EnumType.STRING)
    private PolicyType   type;
    private BigDecimal   coverageAmount;
    private BigDecimal   annualPremium;
    private LocalDate    startDate;
    private LocalDate    endDate;
    @Enumerated(EnumType.STRING)
    private PolicyStatus status = PolicyStatus.ACTIVE;


    public String getPolicyNumber() {
        return id == null ? null : "POL-%05d".formatted(id);
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getCustomerId() { return customerId; }
    public void setCustomerId(Long customerId) { this.customerId = customerId; }
    public Long getDuckId() { return duckId; }
    public void setDuckId(Long duckId) { this.duckId = duckId; }
    public PolicyType getType() { return type; }
    public void setType(PolicyType type) { this.type = type; }
    public BigDecimal getCoverageAmount() { return coverageAmount; }
    public void setCoverageAmount(BigDecimal coverageAmount) { this.coverageAmount = coverageAmount; }
    public BigDecimal getAnnualPremium() { return annualPremium; }
    public void setAnnualPremium(BigDecimal annualPremium) { this.annualPremium = annualPremium; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }
    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }
    public PolicyStatus getStatus() { return status; }
    public void setStatus(PolicyStatus status) { this.status = status; }
}
