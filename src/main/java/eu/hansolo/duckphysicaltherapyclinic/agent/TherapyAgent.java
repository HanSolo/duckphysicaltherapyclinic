package eu.hansolo.duckphysicaltherapyclinic.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.hansolo.duckphysicaltherapyclinic.agent.McpCaller.McpCallException;
import eu.hansolo.duckphysicaltherapyclinic.agent.PlanStore.Plan;
import eu.hansolo.duckphysicaltherapyclinic.domain.AppointmentStatus;
import eu.hansolo.duckphysicaltherapyclinic.service.ClinicException;
import eu.hansolo.duckphysicaltherapyclinic.service.ClinicService;
import eu.hansolo.duckphysicaltherapyclinic.service.Views.AppointmentView;
import eu.hansolo.duckphysicaltherapyclinic.service.Views.DuckView;
import eu.hansolo.duckphysicaltherapyclinic.service.Views.TherapistView;
import eu.hansolo.duckphysicaltherapyclinic.service.Views.TreatmentView;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;


/**
 * The agent of the Duck Physical Therapy Clinic. A referring clinic hands it a duck and a diagnosis, it plans a treatment, asks the insurer, books what the insurer approves and reports back.
 * Same steps as the agents of the two Jakarta EE applications (Jakarta Agentic AI, Payara), written as plain methods because Quarkus has no runtime for that API:
 * <ol>
 *   <li>{@link #start}: a referral arrived (trigger).</li>
 *   <li>{@link #gather}: finds the duck, the catalogue and the free slots (action, pure code).</li>
 *   <li>{@link #judge}: the language model picks treatment, therapist and number of sessions, the code clamps it to the policy (decision).</li>
 *   <li>{@link #negotiate}: asks the insurer for pre-authorization and answers its questions, which may mean fetching the referral document from the referring clinic (action).</li>
 *   <li>{@link #record}: books only the approved sessions and reports to the referring clinic (outcome).</li>
 *   <li>{@link #fallBack}: if something fails, nothing is booked and the referring clinic is told (exception handler).</li>
 * </ol>
 * After each completed session {@link #completeAppointment} bills the insurer.
 */
@ApplicationScoped
public class TherapyAgent {
    private static final Logger   LOG          = Logger.getLogger(TherapyAgent.class.getName());
    /** Hard limits, also written in plain language in policies/therapy-agent-policy.md which the model gets as instructions. Change both together. */
    public  static final int      MAX_SESSIONS = 6;
    public  static final int      MIN_GAP_DAYS = 2;
    public  static final int      FIRST_AFTER  = 2;
    private static final Duration SHORT        = Duration.ofSeconds(30);
    private static final Duration LONG         = Duration.ofSeconds(240);

    @Inject
    ClinicService clinic;
    @Inject
    Llm           llm;
    @Inject
    McpCaller     mcp;
    @Inject
    AgentLog      log;
    @Inject
    PlanStore     plans;
    @Inject
    ObjectMapper  mapper;
    @ConfigProperty(name = "duck.insurer.mcp-url")
    String        insurerUrl;
    @ConfigProperty(name = "duck.clinic.mcp-url")
    String        clinicUrl;
    @ConfigProperty(name = "duck.agent.secret")
    String        secret;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();


    /** What the referring clinic sends. The owner's name and the duck's name are the shared key, the databases have different ids. */
    public record Referral(String referralId, String ownerLastName, String duckName, String diagnosis, int requestedSessions, String referringClinic) {}

    /** What the code found out: the duck, the catalogue. */
    private record Facts(DuckView duck, List<TreatmentView> treatments, List<TherapistView> therapists) {}

    /** The model's choice after clamping. */
    private record Choice(TreatmentView treatment, TherapistView therapist, int sessions, String reason, boolean byModel) {}

    /** A referral that cannot be treated: the referring clinic gets this status and message. */
    private static class Refused extends RuntimeException {
        final String status;
        Refused(final String status, final String message) { super(message); this.status = status; }
    }


    @PreDestroy
    void shutdown() { executor.shutdownNow(); }


    /** Takes the referral and works on it in the background: the model and the insurer take a while. The referring clinic hears the result with report_referral_status. */
    public void receive(final Referral referral) {
        plans.open(referral.referralId(), referral.duckName(), referral.ownerLastName(), referral.diagnosis(), referral.requestedSessions());
        executor.submit(() -> run(referral));
    }

    private void run(final Referral referral) {
        try {
            start(referral);
            final Facts facts = gather(referral);
            final Choice choice = judge(referral, facts);
            final List<LocalDateTime> slots = slots(referral, facts, choice);
            final JsonNode answer = negotiate(referral, facts, choice, slots);
            record(referral, facts, choice, slots, answer);
        } catch (RuntimeException e) {
            fallBack(referral, e);
        }
    }

    void start(final Referral r) {
        log.say(r.referralId(), null, "THOUGHT", "Referral received from " + r.referringClinic() + ": " + r.duckName() + " (owner " + r.ownerLastName() + "), " + r.requestedSessions() + " session(s) suggested. Diagnosis: " + r.diagnosis());
        report(r.referralId(), "PLANNING", "The therapy clinic is planning the treatment and checking the insurance.", null);
    }

    Facts gather(final Referral r) {
        final Optional<DuckView> duck = clinic.listDucks(null).ducks().stream().filter(d -> d.name().equalsIgnoreCase(r.duckName()) && d.ownerName() != null && d.ownerName().toLowerCase().contains(r.ownerLastName().toLowerCase())).findFirst();
        if (duck.isEmpty()) { throw new Refused("REJECTED", "The therapy clinic has no duck " + r.duckName() + " of owner " + r.ownerLastName() + ". The owner has to register the duck first."); }
        final List<TreatmentView> treatments = clinic.listTreatments().treatments();
        final List<TherapistView> therapists = clinic.listTherapists().therapists();
        log.say(r.referralId(), null, "THOUGHT", "Found " + duck.get().name() + " (" + duck.get().breed() + ", owner " + duck.get().ownerName() + ") in our records. Catalogue: " + treatments.size() + " treatments, " + therapists.size() + " therapists.");
        return new Facts(duck.get(), treatments, therapists);
    }

    Choice judge(final Referral r, final Facts f) {
        final int limit = Math.max(1, Math.min(Math.min(r.requestedSessions(), MAX_SESSIONS), MAX_SESSIONS));
        final String prompt = """
                %s

                Referral from %s: the duck %s (%s). Diagnosis: %s. The referring clinic suggests %d session(s).

                Treatments (id: name, minutes, price): %s
                Therapists (id: name, specialty): %s

                Choose one treatment, one therapist and the number of sessions (1 to %d).
                """.formatted(policyText(), r.referringClinic(), f.duck().name(), f.duck().breed(), r.diagnosis(), r.requestedSessions(), list(f.treatments().stream().map(t -> t.id() + ": " + t.name() + ", " + t.durationMinutes() + " min, " + t.price() + " EUR").toList()), list(f.therapists().stream().map(t -> t.id() + ": " + t.name() + ", " + t.specialty()).toList()), limit);
        log.say(r.referralId(), null, "THOUGHT", "Asking the language model which treatment fits the diagnosis (policy: one treatment, at most " + limit + " sessions, from our catalogue).");
        TreatmentView treatment = null;
        TherapistView therapist = null;
        int sessions = limit;
        String reason = "Chosen by the clinic's rules, the model's answer could not be used.";
        boolean byModel = false;
        try {
            final JsonNode json = llm.json(llm.ask(prompt));
            if (json != null) {
                treatment = f.treatments().stream().filter(t -> t.id() == json.path("treatmentId").asLong(-1)).findFirst().orElse(null);
                therapist = f.therapists().stream().filter(t -> t.id() == json.path("therapistId").asLong(-1)).findFirst().orElse(null);
                sessions  = Math.max(1, Math.min(limit, json.path("sessions").asInt(limit)));
                reason    = json.path("reason").asText(reason);
                byModel   = treatment != null && therapist != null;
            }
        } catch (IllegalStateException e) {
            log.say(r.referralId(), null, "ERROR", "The language model failed (" + e.getMessage() + "). Falling back to the clinic's rules.");
        }
        if (treatment == null) { treatment = ruleTreatment(r.diagnosis(), f.treatments()); }
        if (therapist == null) { therapist = f.therapists().get(0); }
        log.say(r.referralId(), null, "THOUGHT", (byModel ? "Model's choice: " : "Rule-based choice: ") + sessions + " x " + treatment.name() + " with " + therapist.name() + " at " + treatment.price() + " EUR per session. " + reason);
        return new Choice(treatment, therapist, sessions, reason, byModel);
    }

    /** Working days from two days ahead, at least two days apart, first free hour between 09:00 and 16:00. */
    List<LocalDateTime> slots(final Referral r, final Facts f, final Choice c) {
        final List<LocalDateTime> slots = new ArrayList<>();
        LocalDate day   = LocalDate.now().plusDays(FIRST_AFTER);
        int       tries = 0;
        while (slots.size() < c.sessions() && tries++ < 90) {
            boolean found = false;
            if (day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY) {
                for (int hour = 9; hour <= 16 && !found; hour++) {
                    final LocalDateTime start = day.atTime(hour, 0);
                    if (clinic.isFree(f.duck().id(), c.therapist().id(), c.treatment().id(), start)) { slots.add(start); found = true; }
                }
            }
            day = day.plusDays(found ? MIN_GAP_DAYS : 1);
        }
        log.say(r.referralId(), null, "POLICY", "Free slots with " + c.therapist().name() + ": " + list(slots.stream().map(LocalDateTime::toString).toList()) + ". Plan price: " + c.treatment().price().multiply(BigDecimal.valueOf(slots.size())) + " EUR.");
        if (slots.isEmpty()) { throw new Refused("REJECTED", "The clinic has no free slot for this treatment in the next weeks."); }
        return slots;
    }

    /** Asks the insurer. It may ask for the referral document first: then the clinic agent is asked for it and the insurer is asked again. */
    JsonNode negotiate(final Referral r, final Facts f, final Choice c, final List<LocalDateTime> slots) {
        String document = null;
        for (int round = 1; round <= 3; round++) {
            final ObjectNode args = mapper.createObjectNode();
            args.put("referralId", r.referralId()).put("ownerLastName", r.ownerLastName()).put("duckName", f.duck().name()).put("provider", "Duck Physical Therapy Clinic").put("treatmentName", c.treatment().name()).put("diagnosis", r.diagnosis());
            args.put("sessions", slots.size()).put("pricePerSession", c.treatment().price().doubleValue()).put("firstSession", slots.get(0).toLocalDate().toString());
            if (document != null) { args.put("referralDocument", document); }
            log.say(r.referralId(), "Insurance Agent", "REQUEST", "Will you pay for " + slots.size() + " x " + c.treatment().name() + " at " + c.treatment().price() + " EUR for " + f.duck().name() + " (owner " + r.ownerLastName() + "), first session " + slots.get(0).toLocalDate() + "? " + (document == null ? "No referral document attached." : "Referral document attached."));
            final JsonNode answer = mcp.call(insurerUrl, "therapy-agent:" + secret, "request_preauthorization", args, LONG);
            final String   status = answer.path("status").asText("?");
            if (status.equals("NEEDS_INFO") && document == null && containsText(answer.path("missing"), "referralDocument")) {
                log.say(r.referralId(), null, "THOUGHT", "The insurer wants the referral document before it decides. It is not ours to give: asking the referring clinic's agent. (" + answer.path("reason").asText() + ")");
                log.say(r.referralId(), "Clinic Agent", "REQUEST", "DuckInsurance needs the referral document of " + r.referralId() + " for the pre-authorization. May I have it?");
                final ObjectNode docArgs = mapper.createObjectNode().put("referralId", r.referralId());
                document = mcp.call(clinicUrl, null, "get_referral_document", docArgs, SHORT).path("document").asText("");
                if (document.isBlank()) { throw new Refused("NOT_COVERED", "The referring clinic did not release the referral document, so the insurer cannot decide."); }
                continue;
            }
            if (status.equals("NEEDS_INFO")) { throw new Refused("NOT_COVERED", "The insurer needs more information than we can give: " + answer.path("reason").asText()); }
            return answer;
        }
        throw new IllegalStateException("The insurer kept asking for more information");
    }

    void record(final Referral r, final Facts f, final Choice c, final List<LocalDateTime> slots, final JsonNode answer) {
        final Plan   plan   = plans.get(r.referralId());
        final String status = answer.path("status").asText();
        plan.treatmentName   = c.treatment().name();
        plan.pricePerSession = c.treatment().price();
        plan.preauthorization = answer.path("reference").asText(null);
        if (!status.equals("APPROVED") && !status.equals("PARTIAL")) {
            plan.status  = "NOT_COVERED";
            plan.message = "DuckInsurance does not pay: " + answer.path("reason").asText();
            log.say(r.referralId(), null, "THOUGHT", "Not approved, so nothing is booked. The owner can pay the treatment herself or ask the insurer again.");
            report(r.referralId(), "NOT_COVERED", plan.message, null);
            return;
        }
        final int    approved  = Math.min(answer.path("approvedSessions").asInt(0), slots.size());
        final double perSession = answer.path("approvedPerSession").asDouble(0);
        final List<String> booked = new ArrayList<>();
        for (int i = 0; i < approved; i++) {
            try {
                final AppointmentView a = clinic.book(f.duck().id(), c.treatment().id(), c.therapist().id(), slots.get(i), "Referral " + r.referralId() + ", pre-authorization " + plan.preauthorization + ", insurer pays " + perSession + " EUR per session");
                plan.appointmentIds.add(a.id());
                booked.add(a.appointmentNumber() + " on " + a.startTime());
            } catch (ClinicException e) {
                log.say(r.referralId(), null, "ERROR", "Could not book " + slots.get(i) + ": " + e.getMessage());
            }
        }
        plan.approvedSessions = booked.size();
        plan.status           = booked.isEmpty() ? "REJECTED" : "SCHEDULED";
        final boolean capped  = perSession < c.treatment().price().doubleValue();
        plan.message = booked.size() + " session(s) booked: " + String.join("; ", booked) + "." + (capped ? " The insurer pays " + perSession + " EUR per session, the owner pays " + c.treatment().price().subtract(BigDecimal.valueOf(perSession)) + " EUR per session." : "") + (booked.size() < r.requestedSessions() ? " Fewer sessions than suggested (" + r.requestedSessions() + "): " + answer.path("reason").asText() : "");
        log.say(r.referralId(), null, "ACTION", plan.message);
        report(r.referralId(), plan.status, plan.message, null);
    }

    void fallBack(final Referral r, final RuntimeException e) {
        final Plan plan = plans.get(r.referralId());
        final String status  = e instanceof Refused refused ? refused.status : "REJECTED";
        final String message = e instanceof Refused ? e.getMessage() : "The therapy clinic's agent could not plan the treatment: " + e.getMessage();
        if (!(e instanceof Refused)) { LOG.log(Level.WARNING, "Therapy agent failed for " + r.referralId(), e); }
        if (plan != null) { plan.status = status; plan.message = message; }
        log.say(r.referralId(), "Clinic Agent", e instanceof Refused ? "ANSWER" : "ERROR", message + " Nothing was booked.");
        report(r.referralId(), status, message, null);
    }

    /** A session is done: mark it completed and bill the insurer for it. Appointments that belong to a referral of this agent are billed, others are only completed. */
    public String completeAppointment(final long appointmentId) {
        final AppointmentView done = clinic.complete(appointmentId);
        final Plan plan = plans.forAppointment(appointmentId);
        if (plan == null) { return "Appointment " + done.appointmentNumber() + " completed. It belongs to no referral of this agent, so no claim was filed."; }
        final String id = plan.referralId;
        log.say(id, null, "ACTION", "Session " + done.appointmentNumber() + " (" + done.treatmentName() + ", " + done.startTime().toLocalDate() + ") completed.");
        final LocalDate day = done.startTime().toLocalDate().isAfter(LocalDate.now()) ? LocalDate.now() : done.startTime().toLocalDate();
        final ObjectNode args = mapper.createObjectNode();
        args.put("referralId", id).put("appointmentNumber", done.appointmentNumber()).put("treatmentName", done.treatmentName()).put("sessionDate", day.toString()).put("amount", done.price().doubleValue());
        log.say(id, "Insurance Agent", "REQUEST", "Billing session " + done.appointmentNumber() + ": " + done.price() + " EUR, treatment " + done.treatmentName() + ", under pre-authorization " + plan.preauthorization + ".");
        String claimNote;
        try {
            final JsonNode claim = mcp.call(insurerUrl, "therapy-agent:" + secret, "submit_provider_claim", args, SHORT);
            claimNote = "Claim " + claim.path("claimNumber").asText("?") + " filed for " + claim.path("claimedAmount").asText("?") + " EUR.";
        } catch (McpCallException | IllegalStateException e) {
            log.say(id, null, "ERROR", "The insurer did not accept the claim: " + e.getMessage());
            claimNote = "The insurer did not accept the claim: " + e.getMessage();
        }
        plan.completedSessions++;
        final boolean all = plan.completedSessions >= plan.appointmentIds.size();
        plan.status  = all ? "COMPLETED" : "IN_TREATMENT";
        plan.message = plan.completedSessions + " of " + plan.appointmentIds.size() + " sessions completed. " + claimNote;
        report(id, plan.status, plan.message, null);
        return plan.message;
    }

    /** The next booked session of a referral, for the demo button. */
    public String completeNext(final String referralId) {
        final Plan plan = plans.find(referralId);
        if (plan == null) { throw ClinicException.invalid("There is no referral to treat" + (referralId == null || referralId.isBlank() ? "" : ": " + referralId)); }
        for (final Long id : plan.appointmentIds) {
            if (clinic.get(id).status() == AppointmentStatus.BOOKED) { return completeAppointment(id); }
        }
        throw ClinicException.invalid("All sessions of " + plan.referralId + " are completed already");
    }

    /** Tells the referring clinic's agent how it goes. A failure is logged, it must not stop the treatment. */
    private void report(final String referralId, final String status, final String message, final ObjectNode details) {
        log.say(referralId, "Clinic Agent", "REQUEST", "Status of " + referralId + ": " + status + ". " + (message == null ? "" : message));
        final ObjectNode args = mapper.createObjectNode().put("referralId", referralId).put("status", status).put("message", message == null ? "" : message);
        try {
            mcp.call(clinicUrl, null, "report_referral_status", args, SHORT);
        } catch (McpCallException | IllegalStateException e) {
            log.say(referralId, null, "ERROR", "Could not tell the referring clinic (" + e.getMessage() + ").");
        }
    }

    private static TreatmentView ruleTreatment(final String diagnosis, final List<TreatmentView> treatments) {
        final String text = diagnosis.toLowerCase();
        final String wanted = text.matches(".*(wing).*") ? "Wing Mobilisation" : text.matches(".*(foot|leg|lame|limp).*") ? "Underwater Treadmill" : text.matches(".*(inflam|swell).*") ? "Laser Therapy" : "Hydrotherapy Session";
        return treatments.stream().filter(t -> t.name().equals(wanted)).findFirst().orElse(treatments.get(0));
    }

    private static boolean containsText(final JsonNode array, final String text) {
        for (final JsonNode n : array) { if (text.equals(n.asText())) { return true; } }
        return false;
    }

    private static String list(final List<String> items) { return items.isEmpty() ? "none" : String.join("; ", items); }

    private static String policyText() {
        try (InputStream in = TherapyAgent.class.getResourceAsStream("/policies/therapy-agent-policy.md")) {
            return in == null ? "(policy file missing)" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "(policy file unreadable)";
        }
    }
}
