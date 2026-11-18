package eu.hansolo.duckinsurance.rest;

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

@Path("/vets")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"ADMIN", "ADJUSTER", "CUSTOMER"})
public class VetResource {

    @Inject
    private VetService vets;


    @GET
    public List<Vet> list() {
        return vets.list();
    }

    @GET
    @Path("/{id}")
    public Vet get(@PathParam("id") long id) {
        return vets.get(id);
    }
}
