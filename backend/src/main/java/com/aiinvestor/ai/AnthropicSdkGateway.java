package com.aiinvestor.ai;

import com.aiinvestor.config.AppProperties;
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicException;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StructuredMessageCreateParams;

import java.time.Duration;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Calls Claude through the official Anthropic Java SDK. The JSON schema is derived from {@link ClaudeAssessmentWire}
 * (structured outputs); the RAW response text is returned untouched for separate storage and validation.
 * Credentials: ANTHROPIC_API_KEY from the environment only — never configuration files, prompts or logs.
 * No temperature/top_p is sent (rejected by current models); thinking uses the model default (adaptive).
 */
public class AnthropicSdkGateway implements ClaudeGateway {
    private final AppProperties.Ai cfg;
    private final AnthropicClient client;

    public AnthropicSdkGateway(AppProperties.Ai cfg) {
        this.cfg = cfg;
        AnthropicClient c = null;
        String key = System.getenv("ANTHROPIC_API_KEY");
        if (key != null && !key.isBlank()) {
            c = AnthropicOkHttpClient.builder().apiKey(key).timeout(Duration.ofSeconds(cfg.timeoutSeconds())).maxRetries(1).build();
        }
        this.client = c;
    }

    @Override public boolean isConfigured() { return client != null; }

    @Override public String modelId() { return cfg.model(); }

    @Override public RawResult complete(String systemPrompt, String userPrompt) {
        if (client == null) throw new AiException(AiException.Kind.NOT_CONFIGURED, "ANTHROPIC_API_KEY is not set");
        StructuredMessageCreateParams<ClaudeAssessmentWire> typed = MessageCreateParams.builder()
                .model(cfg.model())
                .maxTokens((long) cfg.maxOutputTokens())
                .system(systemPrompt)
                .outputConfig(ClaudeAssessmentWire.class)
                .addUserMessage(userPrompt)
                .build();
        MessageCreateParams raw = typed.rawParams();
        OutputConfig oc = raw.outputConfig().orElseThrow();
        MessageCreateParams params = raw.toBuilder()
                .outputConfig(oc.toBuilder().effort(effort(cfg.effort())).build())
                .build();
        long t0 = System.nanoTime();
        try {
            Message m = client.messages().create(params);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            String stop = m.stopReason().map(Object::toString).orElse("");
            if (stop.toLowerCase(Locale.ROOT).contains("refusal")) {
                throw new AiException(AiException.Kind.REFUSED, "model refused the request (stop_reason=refusal)");
            }
            String text = m.content().stream().flatMap(b -> b.text().stream()).map(t -> t.text()).collect(Collectors.joining());
            return new RawResult(text, m.model().asString(), m.usage().inputTokens(), m.usage().outputTokens(), ms, stop);
        } catch (AiException e) {
            throw e;
        } catch (AnthropicException e) {
            String msg = e.getClass().getSimpleName();
            if (e.getCause() instanceof java.net.SocketTimeoutException || msg.toLowerCase(Locale.ROOT).contains("timeout")
                    || (e.getMessage() != null && e.getMessage().toLowerCase(Locale.ROOT).contains("timed out"))) {
                throw new AiException(AiException.Kind.TIMEOUT, "Claude API timed out after " + cfg.timeoutSeconds() + "s", e);
            }
            throw new AiException(AiException.Kind.API_ERROR, "Claude API error: " + msg, e);   // message deliberately omits request/headers
        }
    }

    private static OutputConfig.Effort effort(String s) {
        return switch (s == null ? "medium" : s.toLowerCase(Locale.ROOT)) {
            case "low" -> OutputConfig.Effort.LOW;
            case "high" -> OutputConfig.Effort.HIGH;
            case "xhigh" -> OutputConfig.Effort.XHIGH;
            case "max" -> OutputConfig.Effort.MAX;
            default -> OutputConfig.Effort.MEDIUM;
        };
    }
}
