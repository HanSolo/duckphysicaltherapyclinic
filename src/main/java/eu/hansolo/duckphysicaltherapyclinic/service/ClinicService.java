package eu.hansolo.duckphysicaltherapyclinic.service;

import eu.hansolo.duckphysicaltherapyclinic.domain.Appointment;
import eu.hansolo.duckphysicaltherapyclinic.domain.AppointmentStatus;
import eu.hansolo.duckphysicaltherapyclinic.domain.Duck;
import eu.hansolo.duckphysicaltherapyclinic.domain.Owner;
import eu.hansolo.duckphysicaltherapyclinic.domain.Therapist;
import eu.hansolo.duckphysicaltherapyclinic.domain.Treatment;
import eu.hansolo.duckphysicaltherapyclinic.service.Views.AppointmentList;
import eu.hansolo.duckphysicaltherapyclinic.service.Views.AppointmentView;
import eu.hansolo.duckphysicaltherapyclinic.service.Views.DuckList;
import eu.hansolo.duckphysicaltherapyclinic.service.Views.DuckView;
import eu.hansolo.duckphysicaltherapyclinic.service.Views.OwnerList;
import eu.hansolo.duckphysicaltherapyclinic.service.Views.OwnerView;
import eu.hansolo.duckphysicaltherapyclinic.service.Views.TherapistList;
import eu.hansolo.duckphysicaltherapyclinic.service.Views.TherapistView;
import eu.hansolo.duckphysicaltherapyclinic.service.Views.TreatmentList;
import eu.hansolo.duckphysicaltherapyclinic.service.Views.TreatmentView;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;


@ApplicationScoped
public class ClinicService {
    /** The clinic is open Monday to Friday from 08:00 to 18:00 (local clinic time). A treatment must end by closing time. */
    public static final LocalTime OPENING_TIME = LocalTime.of(8, 0);
    public static final LocalTime CLOSING_TIME = LocalTime.of(18, 0);


    // ******************** Catalogue ********************
    // @Transactional also activates the request context, which the agent needs: it calls these from its own thread, outside any HTTP request.
    @Transactional
    public TreatmentList listTreatments() {
        final List<TreatmentView> list = Treatment.<Treatment>listAll().stream().map(this::toView).toList();
        return new TreatmentList(list.size(), list);
    }

    @Transactional
    public TherapistList listTherapists() {
        final List<TherapistView> list = Therapist.<Therapist>listAll().stream().map(this::toView).toList();
        return new TherapistList(list.size(), list);
    }

    @Transactional
    public OwnerList listOwners() {
        final List<OwnerView> list = Owner.<Owner>listAll().stream().map(this::toView).toList();
        return new OwnerList(list.size(), list);
    }

    @Transactional
    public DuckList listDucks(final Long ownerId) {
        if (ownerId != null) { requireOwner(ownerId); }
        final List<Duck> ducks = ownerId == null ? Duck.listAll() : Duck.list("ownerId", ownerId);
        final List<DuckView> list = ducks.stream().map(this::toView).toList();
        return new DuckList(list.size(), list);
    }


    // ******************** Appointments ********************
    @Transactional
    public AppointmentView book(final Long duckId, final Long treatmentId, final Long therapistId, final LocalDateTime startTime, final String notes) {
        if (duckId == null || treatmentId == null || therapistId == null || startTime == null) { throw ClinicException.invalid("duckId, treatmentId, therapistId and startTime are required"); }
        final Duck      duck      = requireDuck(duckId);
        final Treatment treatment = requireTreatment(treatmentId);
        final Therapist therapist = requireTherapist(therapistId);
        final LocalDateTime start = startTime.withSecond(0).withNano(0);
        final LocalDateTime end   = start.plusMinutes(treatment.durationMinutes);

        if (!start.isAfter(LocalDateTime.now())) { throw ClinicException.invalid("The appointment must start in the future"); }
        if (start.getDayOfWeek() == DayOfWeek.SATURDAY || start.getDayOfWeek() == DayOfWeek.SUNDAY) { throw ClinicException.invalid("The clinic is closed on weekends (open Monday to Friday)"); }
        if (start.toLocalTime().isBefore(OPENING_TIME) || end.toLocalDate().isAfter(start.toLocalDate()) || end.toLocalTime().isAfter(CLOSING_TIME)) { throw ClinicException.invalid("The treatment must take place between " + OPENING_TIME + " and " + CLOSING_TIME + ", it would run from " + start.toLocalTime() + " to " + end.toLocalTime()); }
        if (Appointment.count("therapistId = ?1 and status = ?2 and startTime < ?3 and endTime > ?4", therapistId, AppointmentStatus.BOOKED, end, start) > 0) { throw ClinicException.invalid(therapist.name + " is already booked at that time"); }
        if (Appointment.count("duckId = ?1 and status = ?2 and startTime < ?3 and endTime > ?4", duckId, AppointmentStatus.BOOKED, end, start) > 0) { throw ClinicException.invalid(duck.name + " already has an appointment at that time"); }

        final Appointment appointment = new Appointment();
        appointment.duckId      = duck.id;
        appointment.ownerId     = duck.ownerId;
        appointment.therapistId = therapist.id;
        appointment.treatmentId = treatment.id;
        appointment.startTime   = start;
        appointment.endTime     = end;
        appointment.status      = AppointmentStatus.BOOKED;
        appointment.notes       = notes == null || notes.isBlank() ? null : notes.strip();
        appointment.createdAt   = LocalDateTime.now();
        appointment.persist();
        return toView(appointment);
    }

    /**
     * Marks a booked appointment as completed. For the demo this is allowed before the start time too, so that a whole treatment can be shown in a few minutes.
     */
    @Transactional
    public AppointmentView complete(final Long appointmentId) {
        final Appointment appointment = Appointment.findById(appointmentId);
        if (appointment == null) { throw ClinicException.notFound("Appointment", appointmentId); }
        if (appointment.status != AppointmentStatus.BOOKED) { throw ClinicException.invalid("Only a booked appointment can be completed, this one is " + appointment.status); }
        appointment.status = AppointmentStatus.COMPLETED;
        return toView(appointment);
    }

    /** Is the slot inside opening hours and free for the therapist and the duck? The same rules as {@link #book}, answered with true or false. */
    @Transactional
    public boolean isFree(final Long duckId, final Long therapistId, final Long treatmentId, final LocalDateTime startTime) {
        final Treatment     treatment = requireTreatment(treatmentId);
        final LocalDateTime start     = startTime.withSecond(0).withNano(0);
        final LocalDateTime end       = start.plusMinutes(treatment.durationMinutes);
        if (!start.isAfter(LocalDateTime.now()) || start.getDayOfWeek() == DayOfWeek.SATURDAY || start.getDayOfWeek() == DayOfWeek.SUNDAY) { return false; }
        if (start.toLocalTime().isBefore(OPENING_TIME) || end.toLocalDate().isAfter(start.toLocalDate()) || end.toLocalTime().isAfter(CLOSING_TIME)) { return false; }
        return Appointment.count("therapistId = ?1 and status = ?2 and startTime < ?3 and endTime > ?4", therapistId, AppointmentStatus.BOOKED, end, start) == 0
            && Appointment.count("duckId = ?1 and status = ?2 and startTime < ?3 and endTime > ?4", duckId, AppointmentStatus.BOOKED, end, start) == 0;
    }

    @Transactional
    public AppointmentView get(final Long appointmentId) {
        final Appointment appointment = Appointment.findById(appointmentId);
        if (appointment == null) { throw ClinicException.notFound("Appointment", appointmentId); }
        return toView(appointment);
    }

    @Transactional
    public AppointmentList listForDuck(final Long duckId, final boolean includeCancelled) {
        requireDuck(duckId);
        final List<Appointment> found = includeCancelled ? Appointment.list("duckId = ?1 order by startTime", duckId) : Appointment.list("duckId = ?1 and status <> ?2 order by startTime", duckId, AppointmentStatus.CANCELLED);
        return toList(found);
    }

    @Transactional
    public AppointmentList listForOwner(final Long ownerId, final boolean includeCancelled) {
        requireOwner(ownerId);
        final List<Appointment> found = includeCancelled ? Appointment.list("ownerId = ?1 order by startTime", ownerId) : Appointment.list("ownerId = ?1 and status <> ?2 order by startTime", ownerId, AppointmentStatus.CANCELLED);
        return toList(found);
    }


    // ******************** Helpers ********************
    private AppointmentList toList(final List<Appointment> appointments) {
        final List<AppointmentView> list = appointments.stream().map(this::toView).toList();
        return new AppointmentList(list.size(), list);
    }

    private Duck requireDuck(final Long id) {
        final Duck duck = id == null ? null : Duck.findById(id);
        if (duck == null) { throw ClinicException.notFound("Duck", id); }
        return duck;
    }

    private Owner requireOwner(final Long id) {
        final Owner owner = id == null ? null : Owner.findById(id);
        if (owner == null) { throw ClinicException.notFound("Owner", id); }
        return owner;
    }

    private Therapist requireTherapist(final Long id) {
        final Therapist therapist = id == null ? null : Therapist.findById(id);
        if (therapist == null) { throw ClinicException.notFound("Therapist", id); }
        return therapist;
    }

    private Treatment requireTreatment(final Long id) {
        final Treatment treatment = id == null ? null : Treatment.findById(id);
        if (treatment == null) { throw ClinicException.notFound("Treatment", id); }
        return treatment;
    }

    private OwnerView toView(final Owner o) { return new OwnerView(o.id, o.firstName, o.lastName, o.email, o.birthDate, o.city); }

    private TherapistView toView(final Therapist t) { return new TherapistView(t.id, t.name, t.specialty, t.city); }

    private TreatmentView toView(final Treatment t) { return new TreatmentView(t.id, t.name, t.description, t.durationMinutes, t.price); }

    private DuckView toView(final Duck d) {
        final Owner owner = Owner.findById(d.ownerId);
        return new DuckView(d.id, d.name, d.breed.getLabel(), d.birthDate, d.ownerId, owner == null ? null : owner.fullName());
    }

    private AppointmentView toView(final Appointment a) {
        final Duck      duck      = Duck.findById(a.duckId);
        final Owner     owner     = Owner.findById(a.ownerId);
        final Therapist therapist = Therapist.findById(a.therapistId);
        final Treatment treatment = Treatment.findById(a.treatmentId);
        return new AppointmentView(a.id, a.appointmentNumber(), a.status, a.startTime, a.endTime, a.duckId, duck == null ? null : duck.name, a.ownerId, owner == null ? null : owner.fullName(), a.therapistId, therapist == null ? null : therapist.name, a.treatmentId, treatment == null ? null : treatment.name, treatment == null ? null : treatment.price, a.notes, a.createdAt);
    }
}
