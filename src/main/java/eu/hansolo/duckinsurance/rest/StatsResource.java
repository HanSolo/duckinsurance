package eu.hansolo.duckinsurance.rest;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import eu.hansolo.duckinsurance.service.StatsService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Tag(name = "Stats", description = "Business numbers")
@Path("/stats")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"ADMIN", "ADJUSTER", "CUSTOMER"})
public class StatsResource {

    @Inject
    private StatsService stats;


    @Operation(summary = "Business numbers: owners, ducks, active policies, premium volume, open claims")
    @GET
    public StatsService.Stats get() {
        return stats.current();
    }
}
