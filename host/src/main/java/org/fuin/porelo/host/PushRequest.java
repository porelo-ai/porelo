package org.fuin.porelo.host;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** The forge hook's bounded, single HTTP request, with no path or account lookup. */
record PushRequest(Map<String, String> fields, List<Update> updates) {
    static final int MAXIMUM = 1024 * 1024;
    private static final Set<String> FIELDS =
            Set.of("repository", "quarantine", "objects", "alternates", "pusher");

    PushRequest {
        fields = Map.copyOf(fields);
        updates = List.copyOf(updates);
    }

    record Update(String old, String next, String ref) {
        boolean deletion() {
            return next.chars().allMatch(value -> value == '0');
        }

        String line() {
            return old + " " + next + " " + ref + "\n";
        }
    }

    String lines() {
        return updates.stream().map(Update::line).collect(java.util.stream.Collectors.joining());
    }

    String field(String name) {
        return java.util.Objects.requireNonNull(
                fields.get(name), "The parsed hook request includes all five fields");
    }

    static PushRequest parse(byte[] bytes) {
        try {
            return read(new ByteArrayInputStream(bytes));
        } catch (IOException ex) {
            throw new IllegalStateException("An in-memory request cannot fail to read", ex);
        }
    }

    static PushRequest read(InputStream input) throws IOException {
        return read(input, System::nanoTime);
    }

    static PushRequest read(InputStream input, java.util.function.LongSupplier nanoTime)
            throws IOException {
        final long deadline = nanoTime.getAsLong() + java.time.Duration.ofSeconds(30).toNanos();
        final ByteArrayOutputStream received = new ByteArrayOutputStream();
        byte[] head;
        int end;
        while (true) {
            head = received.toByteArray();
            end = boundary(head);
            if (end >= 0) break;
            if (head.length > 16384) refuse("the hook sent no complete request");
            final byte[] chunk = new byte[4096];
            final int count = read(input, chunk, deadline, nanoTime);
            if (count < 0) refuse("the hook sent no complete request");
            received.write(chunk, 0, count);
        }
        final int length = length(new String(head, 0, end, StandardCharsets.US_ASCII));
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(head, end + 4, head.length - end - 4);
        final byte[] chunk = new byte[65536];
        while (body.size() < length) {
            final int count = read(input, chunk, deadline, nanoTime);
            if (count < 0) refuse("the hook's request ended early");
            body.write(chunk, 0, count);
        }
        // Do not wait for EOF: curl waits for the answer with its sending half still open.
        if (body.size() != length) refuse("the hook's request is longer than it says");
        return body(body.toByteArray());
    }

    private static int read(
            InputStream input,
            byte[] chunk,
            long deadline,
            java.util.function.LongSupplier nanoTime)
            throws IOException {
        checkDeadline(deadline, nanoTime);
        final int count = input.read(chunk);
        checkDeadline(deadline, nanoTime);
        return count;
    }

    private static void checkDeadline(long deadline, java.util.function.LongSupplier nanoTime)
            throws java.net.SocketTimeoutException {
        if (deadline - nanoTime.getAsLong() <= 0)
            throw new java.net.SocketTimeoutException("The hook's request timed out");
    }

    private static int boundary(byte[] bytes) {
        for (int at = 0; at <= bytes.length - 4; at++) {
            if (bytes[at] == '\r'
                    && bytes[at + 1] == '\n'
                    && bytes[at + 2] == '\r'
                    && bytes[at + 3] == '\n') return at;
        }
        return -1;
    }

    private static int length(String head) {
        final String[] lines = head.split("\r\n", -1);
        if (!lines[0].equals("POST /verify HTTP/1.1")) refuse("the hook sent another request");
        final List<String> lengths = new ArrayList<>();
        for (int index = 1; index < lines.length; index++) {
            final int colon = lines[index].indexOf(':');
            if (colon < 0) refuse("the hook's request has a malformed or chunked header");
            final String name = lines[index].substring(0, colon).strip().toLowerCase(Locale.ROOT);
            if (name.equals("transfer-encoding"))
                refuse("the hook's request has a malformed or chunked header");
            if (name.equals("content-length"))
                lengths.add(lines[index].substring(colon + 1).strip());
        }
        if (lengths.size() != 1 || !lengths.getFirst().matches("[0-9]{1,7}"))
            refuse("the hook's request is not 1 to " + MAXIMUM + " bytes");
        final int size = Integer.parseInt(lengths.getFirst());
        if (size == 0 || size > MAXIMUM)
            refuse("the hook's request is not 1 to " + MAXIMUM + " bytes");
        return size;
    }

    private static PushRequest body(byte[] bytes) {
        final String text;
        try {
            text =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes))
                            .toString();
        } catch (CharacterCodingException ex) {
            throw new HostRefusal(64, "the hook's request is not UTF-8");
        }
        final int separator = text.indexOf("\n\n");
        if (separator < 0)
            refuse("the hook's request has no line between its fields and its updates");
        final Map<String, String> fields = new LinkedHashMap<>();
        for (String line : text.substring(0, separator).split("\n", -1)) {
            final int space = line.indexOf(' ');
            final String name = space < 0 ? line : line.substring(0, space);
            if (!FIELDS.contains(name)
                    || fields.putIfAbsent(name, space < 0 ? "" : line.substring(space + 1)) != null)
                refuse("the hook's request names an unknown or repeated field");
        }
        if (!fields.keySet().equals(FIELDS)) refuse("the hook's request lacks a field");
        return new PushRequest(fields, updates(text.substring(separator + 2)));
    }

    static List<Update> updates(String text) {
        final String[] lines = text.split("\n", -1);
        final int count = lines.length - (lines[lines.length - 1].isEmpty() ? 1 : 0);
        final String shape = HostRecords.COMMIT + " " + HostRecords.COMMIT + " refs/[!-~]{1,1000}";
        if (count == 0 || count > 10000)
            refuse("the hook's updates are not 1 to 10000 lines <old> <new> <ref>");
        final List<Update> updates = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            if (!lines[index].matches(shape))
                refuse("the hook's updates are not 1 to 10000 lines <old> <new> <ref>");
            final String[] parts = lines[index].split(" ", 3);
            updates.add(new Update(parts[0], parts[1], parts[2]));
        }
        return List.copyOf(updates);
    }

    private static void refuse(String reason) {
        throw new HostRefusal(64, reason);
    }
}
