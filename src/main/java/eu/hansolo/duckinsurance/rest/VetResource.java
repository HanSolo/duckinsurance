package eu.hansolo.duckinsurance.rest;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import eu.hansolo.duckinsurance.domain.Vet;
import eu.hansolo.duckinsurance.service.VetService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

@Tag(name = "Vets", description = "Vets who treat the ducks")
@Path("/vets")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"ADMIN", "ADJUSTER", "CUSTOMER"})
public class VetResource {

    @Inject
    private VetService vets;


    @Operation(summary = "List vets")
    @GET
    public List<Vet> list() {
        return vets.list();
    }

    @Operation(summary = "Get one vet")
    @GET
    @Path("/{id}")
    public Vet get(@PathParam("id") long id) {
        return vets.get(id);
    }
}
