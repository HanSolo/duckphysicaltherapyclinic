package eu.hansolo.duckphysicaltherapyclinic.rest;

import eu.hansolo.duckphysicaltherapyclinic.service.ClinicException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Map;


@Provider
public class ClinicExceptionMapper implements ExceptionMapper<ClinicException> {

    @Override
    public Response toResponse(final ClinicException e) {
        final Status status = e.isNotFound() ? Status.NOT_FOUND : Status.BAD_REQUEST;
        return Response.status(status).type(MediaType.APPLICATION_JSON).entity(Map.of("error", e.getMessage())).build();
    }
}
