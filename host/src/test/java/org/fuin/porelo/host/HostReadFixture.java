package org.fuin.porelo.host;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.Map;

/** Synthetic host files only; no running account, host configuration, or private keys. */
final class HostReadFixture {
    static final String KEY =
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    static final String PINNED = "SHA256:kmYcvdi2GkPeWxB6XLjrZB8JHsy2Hm8luHMFp9GMvqk";
    static final String FINGERPRINT = "SHA256:" + "A".repeat(43);
    static final Map<String, String> SUDO = Map.of("SUDO_USER", "alice", "SUDO_UID", "1000");
    final Path root;
    final Map<Path, Map<String, Object>> overrides = new HashMap<>();
    java.util.function.Consumer<Path> afterAttributes = path -> {};

    HostReadFixture(Path root) throws IOException {
        this.root = root;
        put("/etc/porelo-host/forge.conf", "host=workshop\n");
    }

    Path path(String name) {
        return root.resolve(name.substring(1));
    }

    Path put(String name, String text) throws IOException {
        final Path path = path(name);
        Files.createDirectories(path.getParent());
        for (Path parent = path.getParent();
                parent != null && parent.startsWith(root);
                parent = parent.getParent()) {
            Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwx------"));
        }
        Files.writeString(path, text);
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        return path;
    }

    void person(String login, String members, String tail) throws IOException {
        put(
                "/etc/porelo-host/people/" + login + ".conf",
                members + "locked=false\nssh=" + KEY + "\nexpires=\n" + tail);
    }

    HostFiles files() {
        return new HostFiles(
                root,
                path -> {
                    final boolean descriptor = path.toString().startsWith("/proc/self/fd/");
                    final Map<String, Object> values =
                            new HashMap<>(
                                    Files.readAttributes(
                                            path,
                                            "unix:uid,mode",
                                            descriptor
                                                    ? new LinkOption[0]
                                                    : new LinkOption[] {
                                                        LinkOption.NOFOLLOW_LINKS
                                                    }));
                    values.put("uid", 0);
                    if (!descriptor && !path.startsWith(root)) {
                        values.put("mode", 040755);
                    }
                    values.putAll(overrides.getOrDefault(path, Map.of()));
                    afterAttributes.accept(path);
                    return values;
                });
    }

    record Answer(int code, String out, String err) {}

    Answer run(Map<String, String> environment, String... args) {
        final StringWriter out = new StringWriter();
        final StringWriter err = new StringWriter();
        final int code =
                PoreloHost.run(
                        args,
                        environment,
                        () -> 0,
                        new PrintWriter(out, true),
                        new PrintWriter(err, true),
                        files(),
                        env ->
                                HostPerson.from(
                                        env,
                                        login -> login + ":x:1000:1000::/nonexistent:/bin/sh\n"));
        return new Answer(code, out.toString(), err.toString());
    }

    // As HostCertificate.entry writes it: no project of its own, only the principal names it. A
    // fixture that added one let the push judge pass here and refuse every real task's push.
    static String task(String login, String task, String fingerprint) {
        return "{\"task\":\""
                + task
                + "\",\"login\":\""
                + login
                + "\",\"fingerprint\":\""
                + fingerprint
                + "\",\"signed_by\":\"SHA256:"
                + "B".repeat(43)
                + "\",\"key_id\":\"porelo task\",\"principals\":[\"task:alpha:"
                + task
                + "\"],\"valid_after\":null,\"valid_before\":\"2030-01-02T03:04:05Z\",\"certificate\":\"ssh-ed25519-cert-v01@openssh.com fixture\"}";
    }
}
