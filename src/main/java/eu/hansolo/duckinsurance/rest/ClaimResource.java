package eu.hansolo.duckinsurance.rest;

import eu.hansolo.duckinsurance.domain.Claim;
import eu.hansolo.duckinsurance.domain.ClaimStatus;
import eu.hansolo.duckinsurance.service.ClaimService;
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

import java.util.List;

@Path("/claims")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"ADMIN", "ADJUSTER", "CUSTOMER"})
public class ClaimResource {

    @Inject
    private ClaimService claims;
    @Context
    private UriInfo      uriInfo;


    @GET
    public List<Claim> list(@QueryParam("status") ClaimStatus status) {
        return claims.list(status);
    }

    @GET
    @Path("/{id}")
    public Claim get(@PathParam("id") long id) {
        return claims.get(id);
    }

    @POST
    public Response submit(@Valid Requests.SubmitClaim request) {
        Claim saved = claims.submit(request.policyId(), request.description(), request.amount(), request.incidentDate(), request.vetId());
        return Response.created(uriInfo.getAbsolutePathBuilder().path(String.valueOf(saved.getId())).build()).entity(saved).build();
    }

    /** Approve or reject an open claim. Adjusters and admins only. */
    @POST
    @Path("/{id}/decision")
    @RolesAllowed({"ADJUSTER", "ADMIN"})
    public Claim decide(@PathParam("id") long id, Requests.Decision decision) {
        return claims.decide(id, decision.approve(), decision.note());
    }
}
