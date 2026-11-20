package eu.hansolo.duckphysicaltherapyclinic.mcp;

import eu.hansolo.duckphysicaltherapyclinic.service.ClinicException;
import eu.hansolo.duckphysicaltherapyclinic.service.ClinicService;
import eu.hansolo.duckphysicaltherapyclinic.service.Views.AppointmentList;
import eu.hansolo.duckphysicaltherapyclinic.service.Views.AppointmentView;
import eu.hansolo.duckphysicaltherapyclinic.service.Views.DuckList;
import eu.hansolo.duckphysicaltherapyclinic.service.Views.OwnerList;
import eu.hansolo.duckphysicaltherapyclinic.service.Views.TherapistList;
import eu.hansolo.duckphysicaltherapyclinic.service.Views.TreatmentList;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.WrapBusinessError;
import jakarta.inject.Inject;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;


/**
 * The MCP face of the clinic. Every tool is a single self-contained request/response, so the server needs no session (MCP spec 2026-07-28).
 * A {@link ClinicException} is reported to the model as a tool error with a readable message, so it can correct itself.
 */
@WrapBusinessError(ClinicException.class)
public class ClinicTools {
    @Inject
    ClinicService clinic;


    @Tool(name = "list_treatments", description = "List all physical therapy treatments for ducks, with id, name, description, duration in minutes and price.")
    TreatmentList listTreatments() { return clinic.listTreatments(); }

    @Tool(name = "list_therapists", description = "List the duck physical therapists, with id, name, specialty and city. Needed to book an appointment.")
    TherapistList listTherapists() { return clinic.listTherapists(); }

    @Tool(name = "list_owners", description = "List all duck owners with their ids. Use it to find an owner id.")
    OwnerList listOwners() { return clinic.listOwners(); }

    @Tool(name = "list_ducks", description = "List the ducks with their ids and owners. Optionally only the ducks of one owner.")
    DuckList listDucks(@ToolArg(description = "Only list the ducks of this owner", required = false) final Long ownerId) { return clinic.listDucks(ownerId); }

    @Tool(name = "book_appointment", description = "Book a physical therapy appointment for a duck. The owner is taken from the duck. The clinic is open Monday to Friday 08:00-18:00 and the treatment has to end by closing time. Fails if the therapist or the duck is already booked at that time.")
    AppointmentView bookAppointment(@ToolArg(description = "Id of the duck to treat") final Long duckId, @ToolArg(description = "Id of the treatment, see list_treatments") final Long treatmentId, @ToolArg(description = "Id of the therapist, see list_therapists") final Long therapistId, @ToolArg(description = "Start in local clinic time, ISO-8601 like 2026-12-01T10:30") final String startTime, @ToolArg(description = "Optional notes for the therapist", required = false) final String notes) {
        return clinic.book(duckId, treatmentId, therapistId, parse(startTime), notes);
    }

    @Tool(name = "get_appointment", description = "Get one appointment by its id.")
    AppointmentView getAppointment(@ToolArg(description = "Id of the appointment") final Long appointmentId) { return clinic.get(appointmentId); }

    @Tool(name = "list_appointments_for_duck", description = "List the appointments of one duck, ordered by start time. Cancelled appointments are left out unless requested.")
    AppointmentList listAppointmentsForDuck(@ToolArg(description = "Id of the duck") final Long duckId, @ToolArg(description = "Also return cancelled appointments, default false", required = false) final Boolean includeCancelled) { return clinic.listForDuck(duckId, Boolean.TRUE.equals(includeCancelled)); }

    @Tool(name = "list_appointments_for_owner", description = "List the appointments of all ducks of one owner, ordered by start time. Cancelled appointments are left out unless requested.")
    AppointmentList listAppointmentsForOwner(@ToolArg(description = "Id of the owner") final Long ownerId, @ToolArg(description = "Also return cancelled appointments, default false", required = false) final Boolean includeCancelled) { return clinic.listForOwner(ownerId, Boolean.TRUE.equals(includeCancelled)); }


    private static LocalDateTime parse(final String text) {
        try {
            return LocalDateTime.parse(text == null ? "" : text.strip());
        } catch (DateTimeParseException e) {
            throw ClinicException.invalid("startTime must look like 2026-12-01T10:30 (ISO-8601, local clinic time), got: " + text);
        }
    }
}
