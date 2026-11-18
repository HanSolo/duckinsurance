package eu.hansolo.duckinsurance.rest;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import eu.hansolo.duckinsurance.domain.Policy;
import eu.hansolo.duckinsurance.service.PolicyService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Tag(name = "Policies", description = "Quotes and policies")
@Path("/policies")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"ADMIN", "ADJUSTER", "CUSTOMER"})
public class PolicyResource {

    @Inject
    private PolicyService policies;
    @Context
    private UriInfo       uriInfo;


    @Operation(summary = "List policies, optionally of one owner")
    @GET
    public List<Policy> list(@QueryParam("customerId") Long customerId) {
        return customerId == null ? policies.list() : policies.forCustomer(customerId);
    }

    @Operation(summary = "Get one policy")
    @GET
    @Path("/{id}")
    public Policy get(@PathParam("id") long id) {
        return policies.get(id);
    }

    /** Calculates the yearly premium without creating anything. */
    @Operation(summary = "Calculate a premium without issuing a policy")
    @POST
    @Path("/quote")
    public Map<String, Object> quote(@Valid Requests.Quote request) {
        return Map.of("duckId", request.duckId(), "type", request.type(), "coverageAmount", request.coverageAmount(), "annualPremium", policies.quote(request.duckId(), request.type(), request.coverageAmount()));
    }

    @Operation(summary = "Issue a one-year policy for a duck")
    @POST
    @RolesAllowed("ADMIN")
    public Response issue(@Valid Requests.IssuePolicy request) {
        LocalDate start = request.startDate() == null ? LocalDate.now() : request.startDate();
        Policy saved = policies.issue(request.duckId(), request.type(), request.coverageAmount(), start);
        return Response.created(uriInfo.getAbsolutePathBuilder().path(String.valueOf(saved.getId())).build()).entity(saved).build();
    }

    @Operation(summary = "Cancel a policy")
    @POST
    @Path("/{id}/cancel")
    @RolesAllowed("ADMIN")
    public Policy cancel(@PathParam("id") long id) {
        return policies.cancel(id);
    }
}
