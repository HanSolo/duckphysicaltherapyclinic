package eu.hansolo.duckphysicaltherapyclinic.agent;

import eu.hansolo.duckphysicaltherapyclinic.agent.PlanStore.Plan;
import eu.hansolo.duckphysicaltherapyclinic.service.ClinicException;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.WrapBusinessError;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;


/**
 * The agent's own tools, next to the plain clinic tools. They are tasks, not CRUD: the clinic's agent decides what it does with a referral and may refuse.
 * propose_treatment_plan returns at once because planning needs the language model and the insurer (a minute or more); the result comes back to the sender with its report_referral_status tool,
 * and get_plan_status shows the state meanwhile.
 */
@Singleton
@WrapBusinessError(ClinicException.class)
public class AgentTools {
    @Inject
    TherapyAgent agent;
    @Inject
    PlanStore    plans;
    @Inject
    AgentLog     log;


    public record Receipt(String referralId, String status, String message) {}

    public record PlanStatus(String referralId, String status, String message, String treatment, String preauthorization, int requestedSessions, int bookedSessions, int completedSessions) {}


    @Tool(name = "propose_treatment_plan", description = "A veterinary clinic refers a duck to the physical therapy clinic. The clinic's agent plans a treatment, asks the owner's insurer for pre-authorization and books the approved sessions. Answers at once with RECEIVED; the result is sent back to the referring clinic with its report_referral_status tool. Needs the owner's consent.")
    Receipt proposeTreatmentPlan(@ToolArg(description = "Referral id that all institutions carry, like REF-0001") final String referralId, @ToolArg(description = "Last name of the duck's owner") final String ownerLastName, @ToolArg(description = "Name of the duck") final String duckName, @ToolArg(description = "Diagnosis and what was done, in one or two sentences") final String diagnosis, @ToolArg(description = "Number of sessions the referring vet suggests, 1 to 6") final Integer requestedSessions, @ToolArg(description = "Name of the referring clinic") final String referringClinic, @ToolArg(description = "True if the owner agreed that the data of this referral is shared with the therapy clinic and the insurer") final Boolean ownerConsent) {
        if (!Boolean.TRUE.equals(ownerConsent)) { throw ClinicException.invalid("Without the owner's consent the clinic does not accept a referral."); }
        if (referralId == null || referralId.isBlank() || ownerLastName == null || ownerLastName.isBlank() || duckName == null || duckName.isBlank() || diagnosis == null || diagnosis.isBlank()) { throw ClinicException.invalid("referralId, ownerLastName, duckName and diagnosis are required"); }
        final int sessions = requestedSessions == null ? 4 : Math.max(1, Math.min(TherapyAgent.MAX_SESSIONS, requestedSessions));
        if (plans.get(referralId.strip()) != null) { throw ClinicException.invalid("The referral " + referralId + " is already known"); }
        log.say(referralId.strip(), null, "ANSWER", "Referral accepted from " + referringClinic + ", I start planning.");
        agent.receive(new TherapyAgent.Referral(referralId.strip(), ownerLastName.strip(), duckName.strip(), diagnosis.strip(), sessions, referringClinic == null ? "the referring clinic" : referringClinic));
        return new Receipt(referralId.strip(), "RECEIVED", "Planning has started, the result follows with report_referral_status.");
    }

    @Tool(name = "get_plan_status", description = "Shows how a referral is going in the physical therapy clinic: RECEIVED, PLANNING, SCHEDULED, NOT_COVERED, REJECTED, IN_TREATMENT, COMPLETED or CANCELLED.")
    PlanStatus getPlanStatus(@ToolArg(description = "Referral id, like REF-0001") final String referralId) {
        final Plan plan = referralId == null ? null : plans.get(referralId.strip());
        if (plan == null) { throw ClinicException.notFound("Referral", referralId); }
        return new PlanStatus(plan.referralId, plan.status, plan.message, plan.treatmentName, plan.preauthorization, plan.requestedSessions, plan.appointmentIds.size(), plan.completedSessions);
    }

    @Tool(name = "cancel_referral", description = "The referring clinic cancels a scheduled referral. Sessions that are completed stay billed, the sessions that are still booked are cancelled, the insurer releases the coverage reserved for them and the referring clinic hears CANCELLED. Only a scheduled referral can be cancelled.")
    String cancelReferral(@ToolArg(description = "Referral id, like REF-0001") final String referralId, @ToolArg(description = "Why the referral is cancelled", required = false) final String reason) { return agent.cancelReferral(referralId, reason); }

    @Tool(name = "complete_appointment", description = "Marks a booked appointment as completed after the session took place. If it belongs to a referral, the clinic's agent bills the insurer for the session.")
    String completeAppointment(@ToolArg(description = "Id of the appointment") final Long appointmentId) { return agent.completeAppointment(appointmentId); }
}
