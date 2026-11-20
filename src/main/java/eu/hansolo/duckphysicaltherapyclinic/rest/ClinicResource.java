package eu.hansolo.duckphysicaltherapyclinic.rest;

import eu.hansolo.duckphysicaltherapyclinic.service.ClinicException;
import eu.hansolo.duckphysicaltherapyclinic.service.ClinicService;
import eu.hansolo.duckphysicaltherapyclinic.service.Views.AppointmentList;
import eu.hansolo.duckphysicaltherapyclinic.service.Views.AppointmentView;
import eu.hansolo.duckphysicaltherapyclinic.service.Views.DuckList;
import eu.hansolo.duckphysicaltherapyclinic.service.Views.OwnerList;
import eu.hansolo.duckphysicaltherapyclinic.service.Views.TherapistList;
import eu.hansolo.duckphysicaltherapyclinic.service.Views.TreatmentList;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.net.URI;
import java.time.LocalDateTime;


/** Plain REST API next to the MCP server. Both call the same {@link ClinicService}. */
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ClinicResource {
    @Inject
    ClinicService clinic;


    public record BookingRequest(Long duckId, Long treatmentId, Long therapistId, LocalDateTime startTime, String notes) {}


    @GET @Path("/treatments")
    public TreatmentList treatments() { return clinic.listTreatments(); }

    @GET @Path("/therapists")
    public TherapistList therapists() { return clinic.listTherapists(); }

    @GET @Path("/owners")
    public OwnerList owners() { return clinic.listOwners(); }

    @GET @Path("/ducks")
    public DuckList ducks(@QueryParam("ownerId") final Long ownerId) { return clinic.listDucks(ownerId); }

    @POST @Path("/appointments")
    public Response book(final BookingRequest request) {
        if (request == null) { throw ClinicException.invalid("A request body is required"); }
        final AppointmentView created = clinic.book(request.duckId(), request.treatmentId(), request.therapistId(), request.startTime(), request.notes());
        return Response.created(URI.create("/api/appointments/" + created.id())).entity(created).build();
    }

    @GET @Path("/appointments/{id}")
    public AppointmentView appointment(@PathParam("id") final Long id) { return clinic.get(id); }

    @GET @Path("/ducks/{duckId}/appointments")
    public AppointmentList forDuck(@PathParam("duckId") final Long duckId, @QueryParam("includeCancelled") final boolean includeCancelled) { return clinic.listForDuck(duckId, includeCancelled); }

    @GET @Path("/owners/{ownerId}/appointments")
    public AppointmentList forOwner(@PathParam("ownerId") final Long ownerId, @QueryParam("includeCancelled") final boolean includeCancelled) { return clinic.listForOwner(ownerId, includeCancelled); }
}
