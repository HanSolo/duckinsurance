package eu.hansolo.duckinsurance.rest;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import eu.hansolo.duckinsurance.domain.Duck;
import eu.hansolo.duckinsurance.service.DuckService;
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

@Tag(name = "Ducks", description = "The insured ducks")
@Path("/ducks")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"ADMIN", "ADJUSTER", "CUSTOMER"})
public class DuckResource {

    @Inject
    private DuckService ducks;
    @Context
    private UriInfo     uriInfo;


    @Operation(summary = "List ducks, optionally of one owner")
    @GET
    public List<Duck> list(@QueryParam("ownerId") Long ownerId) {
        return ducks.list(ownerId);
    }

    @Operation(summary = "Get one duck")
    @GET
    @Path("/{id}")
    public Duck get(@PathParam("id") long id) {
        return ducks.get(id);
    }

    @Operation(summary = "Register a new duck")
    @POST
    @RolesAllowed("ADMIN")
    public Response create(@Valid Duck duck) {
        Duck saved = ducks.create(duck);
        return Response.created(uriInfo.getAbsolutePathBuilder().path(String.valueOf(saved.getId())).build()).entity(saved).build();
    }
}
