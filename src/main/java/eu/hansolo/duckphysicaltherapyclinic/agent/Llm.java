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
 * The language model of this agent, chosen with LLM_PROVIDER (the same settings as for the other two applications):
 * <ul>
 *   <li>{@code ollama} (default): a local Ollama, asked with its native chat API (POST /api/chat, no streaming). Thinking is switched off and the answer is asked as JSON,
 *       so that the reply is short and parseable.</li>
 *   <li>{@code anthropic} (or {@code claude}): the Anthropic Messages API (POST /v1/messages, no streaming), for example Claude at https://api.anthropic.com.
 *       The prompt already asks for JSON, and {@link #json} takes the first JSON object from the answer.</li>
 *   <li>{@code openai}: the Chat Completions API (POST {base}/chat/completions, Bearer key): OpenAI, Groq, OpenRouter, Mistral, Ollama's /v1 and other services in that format.
 *       LLM_BASE_URL includes the version path, for example https://api.openai.com/v1.</li>
 * </ul>
 * Settings: LLM_PROVIDER, LLM_BASE_URL, LLM_MODEL, LLM_API_KEY (anthropic, openai), LLM_MAX_TOKENS (anthropic: default 1024; openai: sent only if set).
 */
@ApplicationScoped
public class Llm {
    @ConfigProperty(name = "duck.llm.provider", defaultValue = "ollama")
    String       provider;
    @ConfigProperty(name = "duck.llm.base-url")
    String       baseUrl;
    @ConfigProperty(name = "duck.llm.model")
    String       model;
    @ConfigProperty(name = "duck.llm.api-key", defaultValue = "ollama")
    String       apiKey;
    @ConfigProperty(name = "duck.llm.max-tokens", defaultValue = "0")
    int          maxTokens;
    @ConfigProperty(name = "duck.llm.timeout-seconds")
    int          timeoutSeconds;
    @Inject
    ObjectMapper mapper;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();


    /** @return the text of the answer without any reasoning block; throws IllegalStateException if the model cannot be reached or answers nothing */
    public String ask(final String prompt) {
        return switch (provider.strip().toLowerCase()) {
            case "anthropic", "claude" -> askAnthropic(prompt);
            case "openai"              -> askOpenAi(prompt);
            case "ollama"              -> askOllama(prompt);
            default                    -> throw new IllegalStateException("Unknown LLM_PROVIDER '" + provider + "'; use ollama, anthropic or openai");
        };
    }

    private String askOllama(final String prompt) {
        final ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("stream", false);
        body.put("think", false);
        body.put("format", "json");
        body.putArray("messages").addObject().put("role", "user").put("content", prompt);
        body.putObject("options").put("temperature", 0.2);
        try {
            final HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl.replaceAll("/+$", "") + "/api/chat")).timeout(Duration.ofSeconds(timeoutSeconds)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
            final JsonNode node = send(request);
            return answer(node.path("message").path("content").asText(""));
        } catch (IOException e) {
            throw new IllegalStateException("The model at " + baseUrl + " is not reachable: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the model", e);
        }
    }

    /** One user message to the Anthropic Messages API; the text blocks of the reply are joined. Temperature is left at the API default, because not every Claude model accepts it. */
    private String askAnthropic(final String prompt) {
        final ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", maxTokens > 0 ? maxTokens : 1024);
        body.putArray("messages").addObject().put("role", "user").put("content", prompt);
        try {
            final HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl.replaceAll("/+$", "") + "/v1/messages")).timeout(Duration.ofSeconds(timeoutSeconds))
                                                   .header("content-type", "application/json").header("x-api-key", apiKey).header("anthropic-version", "2023-06-01")
                                                   .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
            final JsonNode      node = send(request);
            final StringBuilder text = new StringBuilder();
            for (JsonNode block : node.path("content")) {
                if ("text".equals(block.path("type").asText())) { text.append(block.path("text").asText("")); }
            }
            return answer(text.toString());
        } catch (IOException e) {
            throw new IllegalStateException("The model at " + baseUrl + " is not reachable: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the model", e);
        }
    }

    /** One user message to an OpenAI-format Chat Completions API; the text of the first choice. */
    private String askOpenAi(final String prompt) {
        final ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        if (maxTokens > 0) { body.put("max_tokens", maxTokens); }   // only if set: newer OpenAI models reject max_tokens
        body.putArray("messages").addObject().put("role", "user").put("content", prompt);
        try {
            final HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl.replaceAll("/+$", "") + "/chat/completions")).timeout(Duration.ofSeconds(timeoutSeconds))
                                                   .header("content-type", "application/json").header("authorization", "Bearer " + apiKey)
                                                   .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
            final JsonNode node = send(request);
            return answer(node.path("choices").path(0).path("message").path("content").asText(""));
        } catch (IOException e) {
            throw new IllegalStateException("The model at " + baseUrl + " is not reachable: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the model", e);
        }
    }

    private JsonNode send(final HttpRequest request) throws IOException, InterruptedException {
        final HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) { throw new IllegalStateException("The model server answered HTTP " + response.statusCode() + ": " + response.body()); }
        return mapper.readTree(response.body());
    }

    /** The answer without any reasoning block; throws if nothing is left. */
    private static String answer(final String raw) {
        final String text = raw.replaceAll("(?s)<think>.*?</think>", "").strip();
        if (text.isEmpty()) { throw new IllegalStateException("The model returned no answer"); }
        return text;
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
