package eu.hansolo.duckphysicaltherapyclinic;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;


@QuarkusTest
class ClinicTest {
    private static final String VERSION = "2026-07-28";
    private static final String META    = "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"" + VERSION + "\",\"io.modelcontextprotocol/clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}";


    /** One stateless MCP request: no initialize, no session, version and routing info in headers and _meta. */
    private ValidatableResponse mcp(final String method, final String name, final String arguments) {
        final String params = "{" + (name == null ? "" : "\"name\":\"" + name + "\",\"arguments\":" + arguments + ",") + META + "}";
        final String body   = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\",\"params\":" + params + "}";
        var request = given().contentType(ContentType.JSON).accept("application/json, text/event-stream").header("MCP-Protocol-Version", VERSION).header("Mcp-Method", method);
        if (name != null) { request = request.header("Mcp-Name", name); }
        return request.body(body).when().post("/mcp").then().statusCode(200);
    }

    private static String nextMonday10() { return LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.MONDAY)).atTime(10, 0).toString(); }


    @Test
    void mcpToolsListNeedsNoSession() {
        final String body = mcp("tools/list", null, null).extract().asString();
        for (String tool : new String[] { "list_treatments", "book_appointment", "get_appointment", "list_appointments_for_duck", "list_appointments_for_owner" }) {
            org.junit.jupiter.api.Assertions.assertTrue(body.contains(tool), "missing tool " + tool);
        }
    }

    @Test
    void mcpListTreatments() {
        mcp("tools/call", "list_treatments", "{}").body(containsString("Hydrotherapy Session"));
    }

    @Test
    void mcpBookThenGetAndListAndRejectDoubleBooking() {
        final String args = "{\"duckId\":1,\"treatmentId\":1,\"therapistId\":1,\"startTime\":\"" + nextMonday10() + "\"}";
        mcp("tools/call", "book_appointment", args).body(containsString("APT-"));
        mcp("tools/call", "list_appointments_for_duck", "{\"duckId\":1}").body(containsString("Hydrotherapy Session"));
        mcp("tools/call", "list_appointments_for_owner", "{\"ownerId\":1}").body(containsString("Sir Quacksalot"));
        // the same slot again must come back as a tool error, not as an HTTP failure
        mcp("tools/call", "book_appointment", args).body(containsString("already"));
    }

    @Test
    void webUiIsServed() {
        given().when().get("/").then().statusCode(200).body(containsString("Duck Physical Therapy Clinic"));
        given().when().get("/app.js").then().statusCode(200);
        given().when().get("/app.css").then().statusCode(200);
    }

    @Test
    void restTreatments() {
        given().when().get("/api/treatments").then().statusCode(200).body("count", greaterThan(0));
    }

    @Test
    void restBookingRules() {
        given().contentType(ContentType.JSON).body("{\"duckId\":99999,\"treatmentId\":1,\"therapistId\":1,\"startTime\":\"" + nextMonday10() + "\"}").when().post("/api/appointments").then().statusCode(404);
        given().contentType(ContentType.JSON).body("{\"duckId\":2,\"treatmentId\":1,\"therapistId\":2,\"startTime\":\"" + LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.SATURDAY)).atTime(10, 0) + "\"}").when().post("/api/appointments").then().statusCode(400).body("error", containsString("weekends"));
        given().contentType(ContentType.JSON).body("{\"duckId\":2,\"treatmentId\":1,\"therapistId\":2,\"startTime\":\"" + nextMonday10() + "\"}").when().post("/api/appointments").then().statusCode(201).body("duckName", equalTo("Duchess"));
    }
}
