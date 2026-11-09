package org.fuin.porelo.organization.gittree;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class HttpForgeAdminBufferTest {
    @Test
    void authorizationUsesAClearableBufferUntilAnApiRequiresAString() {
        final AtomicReference<Object> sent = new AtomicReference<>();
        final char[] token = "fixture-token".toCharArray();
        try (var api =
                new HttpForgeAdmin(
                        (method, path, authorization, body) -> {
                            sent.set(authorization);
                            assertThat((Object) authorization)
                                    .as(
                                            "the Unix transport must not retain an immutable credential")
                                    .isInstanceOf(byte[].class);
                            assertThat((byte[]) (Object) authorization)
                                    .containsExactly(
                                            "token fixture-token"
                                                    .getBytes(StandardCharsets.US_ASCII));
                            return new HttpForgeAdmin.Answer(204, new byte[0]);
                        },
                        token,
                        false)) {
            api.request("DELETE", "/orgs/alpha", null, 204);
        }
        assertThat((byte[]) sent.get())
                .as("request authorization is cleared after sending")
                .containsOnly((byte) 0);
        assertThat(token)
                .as("the caller still owns its original buffer")
                .containsExactly("fixture-token".toCharArray());
    }
}
