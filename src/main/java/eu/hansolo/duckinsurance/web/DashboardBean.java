package eu.hansolo.duckinsurance.web;

import eu.hansolo.duckinsurance.audit.AuditEntry;
import eu.hansolo.duckinsurance.audit.AuditLog;
import eu.hansolo.duckinsurance.domain.ClaimStatus;
import eu.hansolo.duckinsurance.service.StatsService;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Named
@RequestScoped
public class DashboardBean {

    @Inject
    private StatsService       statsService;
    @Inject
    private AuditLog           auditLog;
    private StatsService.Stats stats;


    @PostConstruct
    void load() {
        stats = statsService.current();
    }


    public long getOwners() { return stats.owners(); }
    public long getDucks() { return stats.ducks(); }
    public long getActivePolicies() { return stats.activePolicies(); }
    public BigDecimal getAnnualPremium() { return stats.annualPremium(); }
    public long getOpenClaims() { return stats.openClaims(); }
    public List<Map.Entry<ClaimStatus, Long>> getClaimCounts() { return List.copyOf(stats.claimsByStatus().entrySet()); }
    public List<AuditEntry> getAudit() { return auditLog.latest(12); }
}
