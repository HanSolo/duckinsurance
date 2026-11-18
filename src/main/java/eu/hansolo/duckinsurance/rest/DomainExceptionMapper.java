package eu.hansolo.duckinsurance.rest;

import eu.hansolo.duckinsurance.domain.DomainException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Map;

@Provider
public class DomainExceptionMapper implements ExceptionMapper<DomainException> {

    @Override
    public Response toResponse(DomainException e) {
        return Response.status(e.getStatus()).type(MediaType.APPLICATION_JSON).entity(Map.of("error", e.getMessage())).build();
    }
}
