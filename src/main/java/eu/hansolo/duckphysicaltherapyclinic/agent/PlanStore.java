package eu.hansolo.duckphysicaltherapyclinic.agent;

import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;


/** The treatment plans this clinic's agent works on, one per referral. In memory only: after a restart the appointments are still in the database, but the plan is gone. */
@ApplicationScoped
public class PlanStore {

    /** One referral as seen by the clinic. status: RECEIVED, PLANNING, SCHEDULED, NOT_COVERED, REJECTED, IN_TREATMENT, COMPLETED, CANCELLED. */
    public static class Plan {
        public String         referralId;
        public String         duckName;
        public String         ownerLastName;
        public String         diagnosis;
        public String         status = "RECEIVED";
        public String         message;
        public String         treatmentName;
        public BigDecimal     pricePerSession;
        public String         preauthorization;
        public int            requestedSessions;
        public int            approvedSessions;
        public int            completedSessions;
        public List<Long>     appointmentIds = new CopyOnWriteArrayList<>();
    }

    private final Map<String, Plan> plans = new ConcurrentHashMap<>();


    public Plan open(final String referralId, final String duckName, final String ownerLastName, final String diagnosis, final int requestedSessions) {
        final Plan plan = new Plan();
        plan.referralId        = referralId;
        plan.duckName          = duckName;
        plan.ownerLastName     = ownerLastName;
        plan.diagnosis         = diagnosis;
        plan.requestedSessions = requestedSessions;
        plans.put(referralId, plan);
        return plan;
    }

    public Plan get(final String referralId) { return plans.get(referralId); }

    /** The plan with this appointment, or null. */
    public Plan forAppointment(final long appointmentId) { return plans.values().stream().filter(p -> p.appointmentIds.contains(appointmentId)).findFirst().orElse(null); }

    /** The plan of the referral, or the one that is being treated if the id is blank. */
    public Plan find(final String referralId) {
        if (referralId != null && !referralId.isBlank()) { return plans.get(referralId.strip()); }
        return plans.values().stream().filter(p -> p.status.equals("SCHEDULED") || p.status.equals("IN_TREATMENT")).findFirst().orElse(null);
    }

    public Collection<Plan> all() { return plans.values(); }

    public void clear() { plans.clear(); }
}
