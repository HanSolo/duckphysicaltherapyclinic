package eu.hansolo.duckphysicaltherapyclinic.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import java.time.LocalDateTime;


@Entity
public class Appointment extends PanacheEntity {
    public Long              duckId;
    public Long              ownerId;
    public Long              therapistId;
    public Long              treatmentId;
    /** Local clinic time. */
    public LocalDateTime     startTime;
    public LocalDateTime     endTime;
    @Enumerated(EnumType.STRING)
    public AppointmentStatus status = AppointmentStatus.BOOKED;
    @Column(length = 500)
    public String            notes;
    public LocalDateTime     createdAt;


    public String appointmentNumber() { return id == null ? null : "APT-%05d".formatted(id); }
}
