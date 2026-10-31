package org.fuin.porelo.app;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.wire.Json;

/**
 * Has the starting person's machine certify a task's key, over the start's own SSH connection.
 *
 * <p>The host asks on one line of its output, {@code {"certify": {"key", "principal", "key_id",
 * "valid_before", "certifiers"}}}, and reads the certificate as the next line of its input. The
 * person's agent never leaves their machine: it signs there, and only the certificate comes back,
 * to be checked here as any other.
 */
final class StdinCertifier implements TaskCertification.Certifier {

    /** The request's one key, which the person's machine looks for among the start's output. */
    static final String REQUEST = "certify";

    /** A certificate line is a few hundred bytes. */
    private static final int MAX_LINE = 16 * 1024;

    private final BufferedReader in;

    private final PrintWriter out;

    private final List<String> certifiers;

    /**
     * Constructor.
     *
     * @param in Where the certificate comes from.
     * @param out Where the request goes.
     * @param certifiers The keys that may certify, so the person's machine picks one its agent
     *     holds.
     */
    StdinCertifier(BufferedReader in, PrintWriter out, List<String> certifiers) {
        this.in = in;
        this.out = out;
        this.certifiers = List.copyOf(certifiers);
    }

    @Override
    public String certify(String key, String principal, String keyId, Instant validBefore) {
        final Map<String, Object> request = new LinkedHashMap<>();
        request.put("key", key);
        request.put("principal", principal);
        request.put("key_id", keyId);
        request.put("valid_before", validBefore.toString());
        request.put("certifiers", certifiers);
        out.println(Json.write(Map.of(REQUEST, request)));
        out.flush();
        final StringBuilder line = new StringBuilder();
        try {
            int read;
            while ((read = in.read()) != -1 && read != '\n') {
                if (line.length() >= MAX_LINE) {
                    throw new TaskCertification.Refused(
                            "the person's machine sent a line longer than a certificate");
                }
                line.append((char) read);
            }
            if (read == -1 && line.isEmpty()) {
                throw new TaskCertification.Refused(
                        "the person's machine sent no certificate for " + principal);
            }
        } catch (IOException ex) {
            throw new TaskCertification.Refused(
                    "the person's machine sent no certificate for "
                            + principal
                            + ": "
                            + ex.getMessage());
        }
        return line.toString().strip();
    }
}
