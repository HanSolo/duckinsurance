package eu.hansolo.duckinsurance.event;

import eu.hansolo.duckinsurance.audit.AuditLog;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;

/** Asynchronous observer: stands in for an e-mail / push notification to the customer. */
@ApplicationScoped
public class ClaimNotifier {

    @Inject
    private AuditLog log;


    void onClaimEvent(@ObservesAsync ClaimEvent event) {
        log.record("notifier", "Claim #" + event.claimId() + " is now " + event.to() + " -> customer notified");
    }
}
