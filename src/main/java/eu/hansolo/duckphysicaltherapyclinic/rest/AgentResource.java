package eu.hansolo.duckphysicaltherapyclinic.rest;

import eu.hansolo.duckphysicaltherapyclinic.agent.AgentLog;
import eu.hansolo.duckphysicaltherapyclinic.agent.PlanStore;
import eu.hansolo.duckphysicaltherapyclinic.agent.TherapyAgent;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;


/** REST face of the agent, for the conversation page of DuckInsurance and for curl: its log, its plans, and the button that completes the next session. */
@Path("/api/agent")
@Produces(MediaType.APPLICATION_JSON)
public class AgentResource {
    @Inject
    AgentLog     log;
    @Inject
    PlanStore    plans;
    @Inject
    TherapyAgent agent;


    @GET @Path("/log")
    public Map<String, Object> log() { return Map.of("source", "Duck Physical Therapy Clinic", "entries", log.list()); }

    @DELETE @Path("/log")
    public Response clear() { log.clear(); return Response.noContent().build(); }

    @GET @Path("/plans")
    public List<PlanStore.Plan> plans() { return List.copyOf(plans.all()); }

    /** Completes the next booked session of the referral (the only one in treatment if no id is given) and lets the agent bill the insurer. */
    @POST @Path("/complete-next")
    public Map<String, String> completeNext(@QueryParam("referralId") final String referralId) { return Map.of("message", agent.completeNext(referralId)); }
}
