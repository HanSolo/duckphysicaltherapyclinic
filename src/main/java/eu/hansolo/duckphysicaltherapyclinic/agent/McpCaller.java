package eu.hansolo.duckphysicaltherapyclinic.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;


/**
 * A minimal MCP client for tool calls, for the stateless spec 2026-07-28: one POST per call, no handshake, no session.
 * <p>
 * Multi round-trip requests: if a server answers {@code resultType: input_required}, the call is repeated with the answers to its {@code inputRequests} and its {@code requestState}, until the server completes (at most {@value #MAX_ROUNDS} repeats).
 * The questions are answered by an {@link InputHandler}, which is code, not a person: this client is an agent talking to another agent. Without a handler the client declares no capabilities and a server that needs input reports an error.
 * It does not do long running tasks or streaming of progress. Wire format as sent by the vendored mcp-jakarta-client of DuckInsurance (headers MCP-Protocol-Version, Mcp-Method, Mcp-Name and the _meta fields of the request).
 */
@ApplicationScoped
public class McpCaller {
    public static final String PROTOCOL_VERSION = "2026-07-28";
    public static final int    MAX_ROUNDS       = 4;

    /** The other side answered, but with an error (a tool error or a protocol error). The message is meant to be shown to people and models. */
    public static class McpCallException extends RuntimeException {
        public McpCallException(final String message) { super(message); }
    }

    /** Answers one question of a server (an elicitation form). */
    @FunctionalInterface
    public interface InputHandler {
        /**
         * @param key     the key of the question, the answer is sent back under it
         * @param request the question as sent: {@code {"method":"elicitation/create","params":{"message":..., "requestedSchema":...}}}
         * @return {@code {"action":"accept","content":{...}}}, or {@code {"action":"decline"}}; use {@link McpCaller#accept} and {@link McpCaller#decline}
         */
        ObjectNode answer(String key, JsonNode request);
    }

    @Inject
    ObjectMapper mapper;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final AtomicLong ids  = new AtomicLong();


    public ObjectNode accept(final ObjectNode content) {
        final ObjectNode answer = mapper.createObjectNode().put("action", "accept");
        answer.set("content", content == null ? mapper.createObjectNode() : content);
        return answer;
    }

    public ObjectNode decline() { return mapper.createObjectNode().put("action", "decline"); }

    public JsonNode call(final String endpoint, final String bearer, final String tool, final ObjectNode arguments, final Duration timeout) { return call(endpoint, bearer, tool, arguments, timeout, null); }

    /**
     * @param bearer  optional bearer token (the insurer needs one), null for none
     * @param handler answers the server's questions, or null if this call cannot be asked anything
     * @return the structured content of the result, or the text of the result parsed as JSON, or a text node
     * @throws McpCallException if the tool failed; IllegalStateException if the server cannot be reached
     */
    public JsonNode call(final String endpoint, final String bearer, final String tool, final ObjectNode arguments, final Duration timeout, final InputHandler handler) {
        final ObjectNode base = mapper.createObjectNode();
        base.put("name", tool);
        base.set("arguments", arguments == null ? mapper.createObjectNode() : arguments);
        ObjectNode params = base.deepCopy();
        try {
            for (int round = 0; ; round++) {
                final JsonNode message = parse(post(endpoint, bearer, tool, params, timeout, handler != null));
                final JsonNode result  = message.path("result");
                if (!"input_required".equals(result.path("resultType").asText())) { return result(tool, message); }
                if (handler == null) { throw new McpCallException(tool + " asked for input, which this call cannot give"); }
                if (round >= MAX_ROUNDS) { throw new McpCallException(tool + " still asks for input after " + MAX_ROUNDS + " rounds"); }
                params = base.deepCopy();
                final ObjectNode answers = params.putObject("inputResponses");
                final Iterator<Map.Entry<String, JsonNode>> questions = result.path("inputRequests").fields();
                while (questions.hasNext()) {
                    final Map.Entry<String, JsonNode> question = questions.next();
                    final ObjectNode answer = handler.answer(question.getKey(), question.getValue());
                    answers.set(question.getKey(), answer == null ? decline() : answer);
                }
                if (result.hasNonNull("requestState")) { params.put("requestState", result.get("requestState").asText()); }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot reach " + endpoint + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling " + tool, e);
        }
    }

    private HttpResponse<String> post(final String endpoint, final String bearer, final String tool, final ObjectNode params, final Duration timeout, final boolean canBeAsked) throws IOException, InterruptedException {
        final ObjectNode withMeta = params.deepCopy();
        final ObjectNode meta = withMeta.putObject("_meta");
        meta.put("io.modelcontextprotocol/protocolVersion", PROTOCOL_VERSION);
        meta.putObject("io.modelcontextprotocol/clientInfo").put("name", "duck-therapy-agent").put("version", "1.0");
        final ObjectNode capabilities = meta.putObject("io.modelcontextprotocol/clientCapabilities");
        if (canBeAsked) { capabilities.putObject("elicitation").putObject("form"); }
        final ObjectNode body = mapper.createObjectNode();
        body.put("jsonrpc", "2.0");
        body.put("id", ids.incrementAndGet());
        body.put("method", "tools/call");
        body.set("params", withMeta);
        final HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoint)).timeout(timeout).header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream").header("MCP-Protocol-Version", PROTOCOL_VERSION).header("Mcp-Method", "tools/call").header("Mcp-Name", tool).POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        if (bearer != null) { request.header("Authorization", "Bearer " + bearer); }
        final HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 401 || response.statusCode() == 403) { throw new McpCallException("The server refused the call (HTTP " + response.statusCode() + "), check the agent secret"); }
        if (response.statusCode() / 100 != 2) { throw new McpCallException("HTTP " + response.statusCode() + " from " + endpoint + ": " + abbreviate(response.body())); }
        return response;
    }

    /** The answer is JSON, or an event stream whose last message with an id is the response. */
    private JsonNode parse(final HttpResponse<String> response) throws IOException {
        final String type = response.headers().firstValue("Content-Type").orElse("");
        if (!type.startsWith("text/event-stream")) { return mapper.readTree(response.body()); }
        JsonNode last = null;
        for (final String line : response.body().split("\n")) {
            if (!line.startsWith("data:")) { continue; }
            final JsonNode message = mapper.readTree(line.substring(5).strip());
            if (message.has("id") && !message.has("method")) { last = message; }
        }
        if (last == null) { throw new McpCallException("The server sent no response"); }
        return last;
    }

    private JsonNode result(final String tool, final JsonNode message) throws IOException {
        if (message.has("error")) { throw new McpCallException(tool + ": " + message.path("error").path("message").asText("protocol error")); }
        final JsonNode result = message.path("result");
        String text = "";
        for (final JsonNode block : result.path("content")) { if ("text".equals(block.path("type").asText())) { text = block.path("text").asText(""); break; } }
        if (result.path("isError").asBoolean(false)) { throw new McpCallException(tool + " failed: " + abbreviate(text)); }
        if (result.has("structuredContent") && !result.path("structuredContent").isNull()) { return result.get("structuredContent"); }
        try {
            return mapper.readTree(text);
        } catch (IOException e) {
            return mapper.getNodeFactory().textNode(text);
        }
    }

    private static String abbreviate(final String text) { return text.length() > 300 ? text.substring(0, 300) + "…" : text; }
}
