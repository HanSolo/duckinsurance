package eu.hansolo.duckinsurance.rest;

import eu.hansolo.duckinsurance.domain.Customer;
import eu.hansolo.duckinsurance.domain.Duck;
import eu.hansolo.duckinsurance.domain.Policy;
import eu.hansolo.duckinsurance.service.CustomerService;
import eu.hansolo.duckinsurance.service.DuckService;
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

import java.util.List;

@Path("/customers")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"ADMIN", "ADJUSTER", "CUSTOMER"})
public class CustomerResource {

    @Inject
    private CustomerService customers;
    @Inject
    private PolicyService   policies;
    @Inject
    private DuckService     ducks;
    @Context
    private UriInfo         uriInfo;


    @GET
    public List<Customer> list(@QueryParam("q") String query) {
        return customers.list(query);
    }

    @GET
    @Path("/{id}")
    public Customer get(@PathParam("id") long id) {
        return customers.get(id);
    }

    @GET
    @Path("/{id}/ducks")
    public List<Duck> ducks(@PathParam("id") long id) {
        customers.get(id);
        return ducks.list(id);
    }

    @GET
    @Path("/{id}/policies")
    public List<Policy> policies(@PathParam("id") long id) {
        customers.get(id);
        return policies.forCustomer(id);
    }

    @POST
    @RolesAllowed("ADMIN")
    public Response create(@Valid Customer customer) {
        Customer saved = customers.create(customer);
        return Response.created(uriInfo.getAbsolutePathBuilder().path(String.valueOf(saved.getId())).build()).entity(saved).build();
    }
}
