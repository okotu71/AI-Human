package com.okotu.aihuman.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.okotu.aihuman.config.PluginConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Client for Ollama's /api/chat endpoint. All calls are async
 * (java.net.http.HttpClient + CompletableFuture): they never block the main thread.
 *
 * <p>Performance tuning lives in two places:
 * <ul>
 *   <li>Per-call ({@link CallOptions}): {@code num_predict}, request timeout,
 *       and {@code num_ctx} - these genuinely differ between a short NPC
 *       reply and a long memory-compression summary, so dialogue and
 *       SummaryService each pass their own values (see
 *       {@code ollama.*} vs {@code ollama.summary-*} in config.yml).</li>
 *   <li>Global (every call, from {@link PluginConfig}): {@code keep_alive},
 *       {@code temperature}, {@code num_batch}, {@code num_thread},
 *       {@code num_gpu}, {@code top_k}, {@code top_p}, {@code repeat_penalty} -
 *       these are llama.cpp/Ollama runtime knobs that don't really need to
 *       differ between dialogue and summary calls.</li>
 * </ul>
 */
public class OllamaClient {

    private final PluginConfig config;
    private final Logger logger;
    private final HttpClient httpClient;

    public OllamaClient(PluginConfig config, Logger logger, Executor executor) {
        this.config = config;
        this.logger = logger;
        this.httpClient = HttpClient.newBuilder()
                // Just for establishing the TCP connection - the much bigger factor for a
                // slow/CPU-only model is generation time, which is bounded separately per
                // call below (CallOptions.timeoutMs), not this constant.
                .connectTimeout(Duration.ofMillis(config.ollamaTimeoutMs))
                .executor(executor)
                .build();
    }

    /**
     * One chat turn: [system prompt] + [history] + [current user message].
     * Uses the dialogue defaults from config.yml ({@code ollama.num-predict},
     * {@code ollama.timeout-ms}, {@code ollama.num-ctx}) - fine for short
     * in-character NPC replies, but NOT for longer generations like memory
     * summaries (see the overload below): a context/timeout/token budget
     * tuned for a short reply will legitimately cut off or time out a much
     * longer summary generation.
     *
     * @param model        Ollama model tag to use (e.g. "qwen2.5:0.5b")
     * @param systemPrompt already-built system prompt (character sheet + memory + knowledge + context)
     * @param messages     history already formatted as role/content pairs (without the system prompt)
     * @param userMessage  the player's latest message
     * @return the NPC's reply text
     */
    public CompletableFuture<String> chat(String model, String systemPrompt,
                                           List<ChatMessage> messages, String userMessage) {
        return chat(model, systemPrompt, messages, userMessage,
                new CallOptions(config.ollamaNumPredict, config.ollamaTimeoutMs, config.ollamaNumCtx));
    }

    /**
     * Same as {@link #chat(String, String, List, String)} but with explicit
     * per-call overrides - e.g. SummaryService needs room (and time, and
     * context window) for a ~200-word summary, well beyond the short-reply
     * defaults used for normal NPC dialogue. Use the {@code ollama.summary-*}
     * config.yml keys for that case rather than guessing at one-size-fits-all values.
     */
    public CompletableFuture<String> chat(String model, String systemPrompt, List<ChatMessage> messages,
                                           String userMessage, CallOptions callOptions) {
        JsonObject body = buildRequestBody(model, systemPrompt, messages, userMessage, callOptions);
        return attemptWithRetries(body, callOptions.timeoutMs(), config.ollamaMaxRetries);
    }

    private CompletableFuture<String> attemptWithRetries(JsonObject body, long timeoutMs, int retriesLeft) {
        return sendRequest(body, timeoutMs).handle((result, throwable) -> {
            if (throwable == null) {
                return CompletableFuture.completedFuture(result);
            }
            if (retriesLeft <= 0) {
                CompletableFuture<String> failed = new CompletableFuture<>();
                failed.completeExceptionally(throwable);
                return failed;
            }
            logger.log(Level.WARNING, "Request to Ollama failed, retrying ("
                    + retriesLeft + " attempts left): " + throwable.getMessage());
            CompletableFuture<String> delayed = new CompletableFuture<>();
            CompletableFuture.delayedExecutor(config.ollamaRetryDelayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .execute(() -> attemptWithRetries(body, timeoutMs, retriesLeft - 1)
                            .whenComplete((r, t) -> {
                                if (t != null) delayed.completeExceptionally(t);
                                else delayed.complete(r);
                            }));
            return delayed;
        }).thenCompose(future -> future);
    }

    private CompletableFuture<String> sendRequest(JsonObject body, long timeoutMs) {
        String url = config.ollamaBaseUrl + "/api/chat";

        if (config.debugLogOllamaCommunication) {
            logger.info("[Ollama DEBUG] Request -> " + url + " (timeout=" + timeoutMs + "ms)\n" + body);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (config.debugLogOllamaCommunication) {
                        logger.info("[Ollama DEBUG] Response (HTTP " + response.statusCode() + ") <- "
                                + url + "\n" + response.body());
                    }
                    if (response.statusCode() / 100 != 2) {
                        throw new CompletionException(
                                new RuntimeException("Ollama responded with HTTP " + response.statusCode()
                                        + ": " + truncate(response.body(), 300)));
                    }
                    return extractContent(response.body());
                });
    }

    private JsonObject buildRequestBody(String model, String systemPrompt,
                                         List<ChatMessage> history, String userMessage, CallOptions callOptions) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("stream", false);
        body.addProperty("keep_alive", config.ollamaKeepAlive);
        body.add("options", buildOptions(callOptions));

        JsonArray messages = new JsonArray();
        messages.add(chatMessageJson("system", systemPrompt));
        for (ChatMessage m : history) {
            messages.add(chatMessageJson(m.role(), m.content()));
        }
        messages.add(chatMessageJson("user", userMessage));
        body.add("messages", messages);

        return body;
    }

    /**
     * Builds the llama.cpp/Ollama "options" object. num_predict/num_ctx come
     * from the per-call {@link CallOptions} (dialogue vs summary differ);
     * everything else is a global performance/sampling knob from config.yml,
     * the same for every call. num_thread/num_gpu are only included when
     * &gt; 0 ("0" in config.yml means "let Ollama auto-detect" for those two).
     */
    private JsonObject buildOptions(CallOptions callOptions) {
        JsonObject options = new JsonObject();
        options.addProperty("num_predict", callOptions.numPredict());
        options.addProperty("num_ctx", callOptions.numCtx());
        options.addProperty("temperature", config.ollamaTemperature);
        options.addProperty("num_batch", config.ollamaNumBatch);
        if (config.ollamaNumThread > 0) {
            options.addProperty("num_thread", config.ollamaNumThread);
        }
        if (config.ollamaNumGpu > 0) {
            options.addProperty("num_gpu", config.ollamaNumGpu);
        }
        options.addProperty("top_k", config.ollamaTopK);
        options.addProperty("top_p", config.ollamaTopP);
        options.addProperty("repeat_penalty", config.ollamaRepeatPenalty);
        return options;
    }

    private JsonObject chatMessageJson(String role, String content) {
        JsonObject o = new JsonObject();
        o.addProperty("role", role);
        o.addProperty("content", content);
        return o;
    }

    private String extractContent(String responseBody) {
        JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
        JsonObject message = root.getAsJsonObject("message");
        if (message == null || !message.has("content")) {
            throw new CompletionException(new RuntimeException("Ollama response is missing message.content"));
        }
        return message.get("content").getAsString().trim();
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /** Role/content pair for the history passed to /api/chat. */
    public record ChatMessage(String role, String content) {
        public static ChatMessage user(String content) {
            return new ChatMessage("user", content);
        }

        public static ChatMessage assistant(String content) {
            return new ChatMessage("assistant", content);
        }
    }

    /** Per-call overrides: the values that genuinely differ between dialogue and memory-compression calls. */
    public record CallOptions(int numPredict, long timeoutMs, int numCtx) {
    }
}
