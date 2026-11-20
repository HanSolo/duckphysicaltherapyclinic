package eu.hansolo.duckphysicaltherapyclinic.service;

import eu.hansolo.duckphysicaltherapyclinic.domain.AppointmentStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;


/** Read models returned by the REST API and the MCP tools. */
public final class Views {

    private Views() {
    }


    public record OwnerView(Long id, String firstName, String lastName, String email, LocalDate birthDate, String city) {}

    public record DuckView(Long id, String name, String breed, LocalDate birthDate, Long ownerId, String ownerName) {}

    public record TherapistView(Long id, String name, String specialty, String city) {}

    public record TreatmentView(Long id, String name, String description, int durationMinutes, BigDecimal price) {}

    public record AppointmentView(Long id, String appointmentNumber, AppointmentStatus status, LocalDateTime startTime, LocalDateTime endTime, Long duckId, String duckName, Long ownerId, String ownerName, Long therapistId, String therapistName, Long treatmentId, String treatmentName, BigDecimal price, String notes, LocalDateTime createdAt) {}

    public record OwnerList(int count, List<OwnerView> owners) {}

    public record DuckList(int count, List<DuckView> ducks) {}

    public record TherapistList(int count, List<TherapistView> therapists) {}

    public record TreatmentList(int count, List<TreatmentView> treatments) {}

    public record AppointmentList(int count, List<AppointmentView> appointments) {}
}
