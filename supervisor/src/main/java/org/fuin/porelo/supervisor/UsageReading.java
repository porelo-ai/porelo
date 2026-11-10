package org.fuin.porelo.supervisor;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;

/** Bounded, disposable observation of generation metadata, with no effect on the relay. */
final class UsageReading {
    static final int LIMIT = VaultProxy.USAGE_LIMIT;
    private static final int TOOL_LIMIT = 256;
    private final String format;
    private final @Nullable String modelAsked;
    private final boolean asked;
    private final boolean count;
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private final Map<String, String> calls = new LinkedHashMap<>();
    private final Map<String, Long> tools = new LinkedHashMap<>();
    private final Map<String, String> cost = new LinkedHashMap<>();
    private @Nullable String model;
    private @Nullable String finishReason;
    private @Nullable Long input;
    private @Nullable Long cacheRead;
    private @Nullable Long cacheWrite;
    private @Nullable Long output;
    private @Nullable Long reasoning;
    private @Nullable Long rawInput;
    private @Nullable Long rawOutput;
    private boolean stream;
    private boolean framed;
    private boolean failed;
    private boolean complete;
    private boolean error;
    private boolean carriage;
    private int lineBytes;
    private int status;
    private int geminiCalls;
    private final String time = Instant.now().toString();

    UsageReading(String path, byte[] request, boolean asked, boolean count) {
        this.format =
                java.util.Objects.requireNonNull(
                        format(path), "only generation paths are observed");
        this.asked = asked;
        this.count = count;
        String requested = null;
        try {
            if (request.length <= LIMIT)
                requested =
                        name(
                                object(
                                                Json.parseDecimals(
                                                        new String(
                                                                request, StandardCharsets.UTF_8)))
                                        .get("model"));
        } catch (RuntimeException | StackOverflowError ex) {
            // Request metadata is optional and must never make a valid relay fail.
        }
        if (requested == null && format.equals("gemini")) {
            final String clean = path.split("\\?", 2)[0];
            final int models = clean.lastIndexOf("/models/");
            final int colon = clean.lastIndexOf(':');
            if (models >= 0 && colon > models) requested = name(clean.substring(models + 8, colon));
        }
        modelAsked = requested;
    }

    /** Tells whether a request generates: reading a stored answer back bills nothing. */
    static boolean observes(String method, String target) {
        return method.equals("POST") && format(target) != null;
    }

    static @Nullable String format(String target) {
        final String path = target.split("\\?", 2)[0];
        if (path.endsWith("/chat/completions")) return "chat_completions";
        if (path.endsWith("/responses")) return "responses";
        if (path.endsWith("/v1/messages")) return "messages";
        if (path.endsWith(":generateContent") || path.endsWith(":streamGenerateContent"))
            return "gemini";
        return null;
    }

    void start(boolean events, int code) {
        stream |= events;
        status = code;
        error |= code < 200 || code >= 300;
    }

    /** Marks an answer the agent never got, whatever the provider's status said. */
    void withhold() {
        error = true;
    }

    void invalidate() {
        failed = true;
        buffer.reset();
    }

    void accept(byte[] bytes, int length) {
        if (failed) return;
        if (!framed) {
            if (length > LIMIT - buffer.size()) {
                invalidate();
                return;
            }
            buffer.write(bytes, 0, length);
            final byte[] first = buffer.toByteArray();
            if (!stream && VaultProxy.undecided(first)) return;
            // A streamed answer can be labeled JSON; its opening, not the label, carries usage.
            stream |= VaultProxy.opensAsEvents(first);
            framed = true;
            buffer.reset();
            acceptFramed(first, first.length);
            return;
        }
        acceptFramed(bytes, length);
    }

    private void acceptFramed(byte[] bytes, int length) {
        try {
            for (int i = 0; i < length && !failed; i++) {
                final int b = bytes[i] & 255;
                if (stream && (b == '\r' || b == '\n')) {
                    if (b == '\n' && carriage) {
                        carriage = false;
                        continue;
                    }
                    carriage = b == '\r';
                    if (lineBytes == 0) event();
                    else {
                        if (buffer.size() >= LIMIT) {
                            invalidate();
                            return;
                        }
                        buffer.write('\n');
                        lineBytes = 0;
                    }
                } else {
                    carriage = false;
                    if (buffer.size() >= LIMIT) {
                        invalidate();
                        return;
                    }
                    buffer.write(b);
                    lineBytes++;
                }
            }
        } catch (RuntimeException | StackOverflowError ex) {
            invalidate();
        }
    }

    private void event() {
        final String text = buffer.toString(StandardCharsets.UTF_8);
        buffer.reset();
        lineBytes = 0;
        final StringBuilder data = new StringBuilder();
        String event = "";
        for (final String line : text.split("\n")) {
            if (line.startsWith("data:")) {
                if (!data.isEmpty()) data.append('\n');
                data.append(line.substring(5).stripLeading());
            } else if (line.startsWith("event:")) event = line.substring(6).strip();
        }
        if (event.equals("error")) error = true;
        if (data.toString().equals("[DONE]")) {
            complete = true;
            return;
        }
        if (!data.isEmpty()) read(object(Json.parseDecimals(data.toString())), event);
    }

    private void read(Map<?, ?> root, String event) {
        final String type = root.get("type") instanceof String s ? s : event;
        if (type.equals("error") || type.equals("response.failed") || root.get("error") != null)
            error = true;
        switch (format) {
            case "chat_completions" -> chat(root);
            case "responses" -> responses(root, type);
            case "messages" -> messages(root, type);
            case "gemini" -> gemini(root);
            default -> {}
        }
    }

    private void chat(Map<?, ?> root) {
        model = last(model, name(root.get("model")));
        final Map<?, ?> usage = object(root.get("usage"));
        rawInput = last(rawInput, number(usage.get("prompt_tokens")));
        cacheRead =
                last(
                        cacheRead,
                        number(object(usage.get("prompt_tokens_details")).get("cached_tokens")));
        input = uncached(rawInput, cacheRead);
        output = last(output, number(usage.get("completion_tokens")));
        reasoning =
                last(
                        reasoning,
                        number(
                                object(usage.get("completion_tokens_details"))
                                        .get("reasoning_tokens")));
        costs(usage);
        final List<?> choices = array(root.get("choices"));
        for (final Object each : choices) {
            final Map<?, ?> choice = object(each);
            finishReason = last(finishReason, name(choice.get("finish_reason")));
            final Map<?, ?> message = object(choice.get(stream ? "delta" : "message"));
            int at = 0;
            for (final Object call : array(message.get("tool_calls"))) {
                final Map<?, ?> tool = object(call);
                final String key =
                        "chat/"
                                + index(choice.get("index"))
                                + "/"
                                + (stream ? index(tool.get("index")) : at++);
                tool(key, object(tool.get("function")).get("name"), stream);
            }
        }
        // Some providers repeat the count in content chunks, so that alone does not end an answer.
        if (!usage.isEmpty() && (choices.isEmpty() || finishReason != null)) complete = true;
    }

    private void responses(Map<?, ?> root, String type) {
        final Map<?, ?> response = root.get("response") instanceof Map<?, ?> nested ? nested : root;
        model = last(model, name(response.get("model")));
        finishReason = last(finishReason, name(response.get("status")));
        final Map<?, ?> usage = object(response.get("usage"));
        rawInput = last(rawInput, number(usage.get("input_tokens")));
        cacheRead =
                last(
                        cacheRead,
                        number(object(usage.get("input_tokens_details")).get("cached_tokens")));
        input = uncached(rawInput, cacheRead);
        output = last(output, number(usage.get("output_tokens")));
        reasoning =
                last(
                        reasoning,
                        number(object(usage.get("output_tokens_details")).get("reasoning_tokens")));
        costs(usage);
        if (type.equals("response.completed") || type.equals("response.incomplete"))
            complete = true;
        if (type.equals("response.output_item.added") || type.equals("response.output_item.done")) {
            final Map<?, ?> item = object(root.get("item"));
            if ("function_call".equals(item.get("type")))
                tool("responses/" + index(root.get("output_index")), item.get("name"), false);
        }
        int at = 0;
        for (final Object each : array(response.get("output"))) {
            final Map<?, ?> item = object(each);
            if ("function_call".equals(item.get("type")))
                tool("responses/" + at, item.get("name"), false);
            at++;
        }
    }

    private void messages(Map<?, ?> root, String type) {
        final Map<?, ?> message = type.equals("message_start") ? object(root.get("message")) : root;
        model = last(model, name(message.get("model")));
        final Map<?, ?> usage = object(message.get("usage"));
        input = last(input, number(usage.get("input_tokens")));
        cacheRead = last(cacheRead, number(usage.get("cache_read_input_tokens")));
        cacheWrite = last(cacheWrite, number(usage.get("cache_creation_input_tokens")));
        // The opening count is a placeholder, not what was generated.
        if (!type.equals("message_start"))
            output = last(output, number(usage.get("output_tokens")));
        finishReason = last(finishReason, name(message.get("stop_reason")));
        finishReason = last(finishReason, name(object(root.get("delta")).get("stop_reason")));
        if (type.equals("message_stop")) complete = true;
        if (type.equals("content_block_start")) {
            final Map<?, ?> block = object(root.get("content_block"));
            if ("tool_use".equals(block.get("type")))
                tool("messages/" + index(root.get("index")), block.get("name"), false);
        }
        int at = 0;
        for (final Object each : array(message.get("content"))) {
            final Map<?, ?> block = object(each);
            if ("tool_use".equals(block.get("type")))
                tool("messages/" + at, block.get("name"), false);
            at++;
        }
    }

    private void gemini(Map<?, ?> outer) {
        // Code Assist's methods wrap the same answer.
        final Map<?, ?> root = outer.get("response") instanceof Map<?, ?> inner ? inner : outer;
        model = last(model, name(root.get("modelVersion")));
        final Map<?, ?> usage = object(root.get("usageMetadata"));
        rawInput = last(rawInput, number(usage.get("promptTokenCount")));
        cacheRead = last(cacheRead, number(usage.get("cachedContentTokenCount")));
        input = uncached(rawInput, cacheRead);
        rawOutput = last(rawOutput, number(usage.get("candidatesTokenCount")));
        reasoning = last(reasoning, number(usage.get("thoughtsTokenCount")));
        output =
                rawOutput == null && reasoning == null
                        ? null
                        : Math.addExact(
                                rawOutput == null ? 0 : rawOutput,
                                reasoning == null ? 0 : reasoning);
        for (final Object each : array(root.get("candidates"))) {
            final Map<?, ?> candidate = object(each);
            final String reason = name(candidate.get("finishReason"));
            finishReason = last(finishReason, reason);
            if (reason != null) complete = true;
            for (final Object part : array(object(candidate.get("content")).get("parts"))) {
                final Map<?, ?> function = object(object(part).get("functionCall"));
                if (!function.isEmpty()) {
                    tool("gemini/" + geminiCalls++, function.get("name"), false);
                }
            }
        }
    }

    private void costs(Map<?, ?> usage) {
        for (final String key : List.of("cost", "cost_in_usd_ticks")) {
            final Object value = usage.get(key);
            if (value instanceof java.math.BigDecimal n && n.signum() >= 0) {
                if (n.precision() > 64 || Math.abs((long) n.scale()) > 64)
                    throw new IllegalArgumentException("Invalid reported cost");
                cost.put(key, n.stripTrailingZeros().toPlainString());
            } else if (value instanceof String text
                    && text.matches("[0-9]{1,18}(\\.[0-9]{1,18})?")) {
                cost.put(key, text);
            }
        }
    }

    private void tool(String key, @Nullable Object value, boolean fragment) {
        final String next = name(value);
        if (next == null) return;
        final String previous = calls.get(key);
        if (previous == null && calls.size() >= TOOL_LIMIT) {
            invalidate();
            return;
        }
        final String joined = fragment && previous != null ? name(previous + next) : next;
        if (joined == null) {
            invalidate();
            return;
        }
        calls.put(key, joined);
    }

    Map<String, Object> finish(@Nullable String closedBy) {
        try {
            if (!stream) {
                complete = true;
                if (!failed)
                    read(object(Json.parseDecimals(buffer.toString(StandardCharsets.UTF_8))), "");
            }
        } catch (RuntimeException | StackOverflowError ex) {
            invalidate();
        }
        tools.clear();
        calls.values().forEach(value -> tools.merge(value, 1L, Long::sum));
        final boolean known = (count || !stream || !format.equals("chat_completions")) && !failed;
        final String end = error ? "error" : closedBy != null || !complete ? "aborted" : "complete";
        final Map<String, Object> line = new LinkedHashMap<>();
        line.put("time", time);
        line.put("format", format);
        line.put("model_asked", modelAsked);
        line.put("model_answered", model);
        line.put("input", known ? input : null);
        line.put("cache_read", known ? cacheRead : null);
        line.put("cache_write", known ? cacheWrite : null);
        line.put("output", known ? output : null);
        line.put("reasoning", known ? reasoning : null);
        line.put("asked", asked);
        line.put("tools", new LinkedHashMap<>(tools));
        line.put("reported_cost", known ? new LinkedHashMap<>(cost) : Map.of());
        line.put("finish_reason", finishReason);
        line.put("end", end);
        line.put(
                "closed_by",
                end.equals("aborted") ? closedBy == null ? "provider" : closedBy : null);
        line.put("status", status);
        return line;
    }

    private static Map<?, ?> object(@Nullable Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private static List<?> array(@Nullable Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private static long index(@Nullable Object value) {
        return value instanceof Number n ? n.longValue() : 0;
    }

    private static @Nullable String name(@Nullable Object value) {
        return value instanceof String text && text.matches("[A-Za-z0-9_./:@+~\\-]{1,200}")
                ? text
                : null;
    }

    private static @Nullable Long number(@Nullable Object value) {
        if (value == null) return null;
        if (!(value instanceof java.math.BigDecimal n) || n.signum() < 0)
            throw new IllegalArgumentException("Invalid usage count");
        return n.longValueExact();
    }

    private static @Nullable Long uncached(@Nullable Long total, @Nullable Long cached) {
        if (total == null) return null;
        if (cached != null && cached > total)
            throw new IllegalArgumentException("Invalid cached count");
        return total - (cached == null ? 0 : cached);
    }

    private static <T> @Nullable T last(@Nullable T before, @Nullable T next) {
        return next == null ? before : next;
    }
}
