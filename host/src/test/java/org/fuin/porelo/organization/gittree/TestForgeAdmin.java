package org.fuin.porelo.organization.gittree;

import java.io.IOException;

/** Supplies request-level forge replies without a socket in a restricted test process. */
public final class TestForgeAdmin {
    private TestForgeAdmin() {}

    /** One HTTP exchange as seen by the request validator. */
    @FunctionalInterface
    public interface Exchange {
        /** Reply without retaining a request's credential. */
        Response request(String method, String path, byte[] authorization, byte[] body)
                throws IOException;
    }

    /** A raw HTTP status and its JSON bytes. */
    public record Response(int status, byte[] body) {}

    /** Wrap the production validation around a supplied exchange. */
    public static HttpForgeAdmin create(char[] token, boolean central, Exchange exchange) {
        return new HttpForgeAdmin(
                (method, path, authorization, body) -> {
                    final Response reply = exchange.request(method, path, authorization, body);
                    return new HttpForgeAdmin.Answer(reply.status(), reply.body());
                },
                token,
                central);
    }
}
