package eu.hansolo.duckphysicaltherapyclinic.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;


/**
 * The language model of this agent: a local Ollama, asked with its native chat API (POST /api/chat, no streaming). Thinking is switched off and the answer is asked as JSON,
 * so that the reply is short and parseable. Settings: LLM_BASE_URL and LLM_MODEL, the same as for the other two applications.
 */
@ApplicationScoped
public class Llm {
    @ConfigProperty(name = "duck.llm.base-url")
    String       baseUrl;
    @ConfigProperty(name = "duck.llm.model")
    String       model;
    @ConfigProperty(name = "duck.llm.timeout-seconds")
    int          timeoutSeconds;
    @Inject
    ObjectMapper mapper;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();


    /** @return the text of the answer without any reasoning block; throws IllegalStateException if the model cannot be reached or answers nothing */
    public String ask(final String prompt) {
        final ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("stream", false);
        body.put("think", false);
        body.put("format", "json");
        body.putArray("messages").addObject().put("role", "user").put("content", prompt);
        body.putObject("options").put("temperature", 0.2);
        try {
            final HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl.replaceAll("/+$", "") + "/api/chat")).timeout(Duration.ofSeconds(timeoutSeconds)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
            final HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) { throw new IllegalStateException("The model server answered HTTP " + response.statusCode() + ": " + response.body()); }
            final JsonNode node = mapper.readTree(response.body());
            final String   text = node.path("message").path("content").asText("").replaceAll("(?s)<think>.*?</think>", "").strip();
            if (text.isEmpty()) { throw new IllegalStateException("The model returned no answer"); }
            return text;
        } catch (IOException e) {
            throw new IllegalStateException("The model at " + baseUrl + " is not reachable: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the model", e);
        }
    }

    /** The first JSON object in a model answer; small models add text around it. */
    public JsonNode json(final String answer) {
        try {
            final int from = answer.indexOf('{');
            final int to   = answer.lastIndexOf('}');
            return mapper.readTree(answer.substring(from, to + 1));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
