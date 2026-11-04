package org.fuin.porelo.supervisor;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;

/** Optional request metadata and the sole request rewrite needed for a usage chunk. */
record UsageRequest(byte[] body, byte[] plain, boolean asked, boolean rewritten) {
    static UsageRequest prepare(
            String method, String target, byte[] body, @Nullable String coding, boolean enabled) {
        if (UsageReading.format(target) == null)
            return new UsageRequest(body, new byte[0], false, false);
        byte[] plain = body;
        try {
            // Long contexts pass the parser's bound in ordinary work, and a request left to the
            // agent goes uncounted, so only one that is rewritten may be read up to the broker's.
            final boolean rewrites =
                    enabled
                            && method.equals("POST")
                            && "chat_completions".equals(UsageReading.format(target));
            final int bound = rewrites ? VaultProxy.BODY_LIMIT : UsageReading.LIMIT;
            // As the broker reads the header when it assesses a request.
            final String encoding =
                    coding == null
                            ? ""
                            : java.util.Arrays.stream(coding.split(","))
                                    .map(part -> part.strip().toLowerCase(java.util.Locale.ROOT))
                                    .filter(part -> !part.isEmpty() && !part.equals("identity"))
                                    .collect(java.util.stream.Collectors.joining(","));
            if (!encoding.isEmpty()) {
                try (InputStream in =
                        switch (encoding) {
                            case "gzip", "x-gzip" ->
                                    new java.util.zip.GZIPInputStream(
                                            new ByteArrayInputStream(body));
                            case "deflate" ->
                                    new java.util.zip.InflaterInputStream(
                                            new ByteArrayInputStream(body));
                            case "zstd" ->
                                    new io.airlift.compress.v3.zstd.ZstdInputStream(
                                            new ByteArrayInputStream(body));
                            default -> InputStream.nullInputStream();
                        }) {
                    plain = in.readNBytes(bound + 1);
                }
            }
            if (plain.length > bound) return new UsageRequest(body, new byte[0], false, false);
            if (!rewrites) return new UsageRequest(body, plain, false, false);
            if (!(Json.parseDecimals(new String(plain, StandardCharsets.UTF_8))
                            instanceof Map<?, ?> root)
                    || !Boolean.TRUE.equals(root.get("stream")))
                return new UsageRequest(body, plain, false, false);
            final Map<String, Object> changed = copy(root);
            final Map<String, Object> options =
                    root.get("stream_options") instanceof Map<?, ?> map
                            ? copy(map)
                            : new LinkedHashMap<>();
            options.put("include_usage", true);
            changed.put("stream_options", options);
            return new UsageRequest(
                    Json.write(changed).getBytes(StandardCharsets.UTF_8), plain, true, true);
        } catch (java.io.IOException | RuntimeException | StackOverflowError ex) {
            // A usage request is optional; the original bytes still belong to the provider.
            return new UsageRequest(body, new byte[0], false, false);
        }
    }

    private static Map<String, Object> copy(Map<?, ?> source) {
        final Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }
}
