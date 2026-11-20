package eu.hansolo.duckinsurance.agent;

import eu.hansolo.duckinsurance.domain.Claim;
import eu.hansolo.duckinsurance.domain.DomainException;
import eu.hansolo.duckinsurance.service.ClaimService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;

import java.io.StringReader;
import java.time.LocalDateTime;

/** Books the appointment that the fraud agent proposed for a claim. This writes to the physical therapy clinic, so it only runs when an adjuster presses the button. */
@ApplicationScoped
public class PhysioBooking {

    @Inject
    private ClaimService claims;
    @Inject
    private PhysioClient physio;


    /** The text of the proposal of a claim, or null. */
    public static String text(String proposal) {
        return proposal == null ? null : read(proposal).getString("text", null);
    }

    /** @return the number of the new appointment */
    public String book(long claimId) {
        Claim claim = claims.get(claimId);
        if (claim.getProposal() == null) throw DomainException.conflict("There is no proposal for this claim");
        JsonObject p = read(claim.getProposal());
        String number;
        try {
            number = physio.book(p.getJsonNumber("duckId").longValue(), p.getJsonNumber("treatmentId").longValue(), p.getJsonNumber("therapistId").longValue(), LocalDateTime.parse(p.getString("start")), "Booked by DuckInsurance for claim " + claim.getClaimNumber());
        } catch (IllegalStateException | ClinicUnavailableException e) {
            throw DomainException.conflict("The appointment could not be booked: " + e.getMessage());
        }
        claims.proposalBooked(claimId, number);
        return number;
    }

    private static JsonObject read(String json) {
        return Json.createReader(new StringReader(json)).readObject();
    }
}
