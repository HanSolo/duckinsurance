package eu.hansolo.duckinsurance.rest;

import eu.hansolo.duckinsurance.service.StatsService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/stats")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"ADMIN", "ADJUSTER", "CUSTOMER"})
public class StatsResource {

    @Inject
    private StatsService stats;


    @GET
    public StatsService.Stats get() {
        return stats.current();
    }
}
