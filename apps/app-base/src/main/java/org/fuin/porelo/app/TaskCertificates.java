package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.fuin.porelo.core.net.TrustedCertificates;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandException;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.runtime.Containerfile;
import org.fuin.porelo.runtime.Podman;
import org.jspecify.annotations.Nullable;

/** The host authority explicitly handed to a task, isolated from host-client trust. */
public final class TaskCertificates {

    /** A requested task authority could not be safely provided. */
    public static final class Refused extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        private Refused(String message) {
            super(message);
        }

        private Refused(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Name in the account's configuration and in a task's state. */
    public static final String FILE = "task-ca-certificates.pem";

    /** Fixed mount outside the directory of files handed in by a person. */
    public static final String MOUNT = Containerfile.HAND_IN_ROOT + "/host-ca.pem";

    private static final Pattern PEM =
            Pattern.compile(
                    "(?:\\s*-----BEGIN CERTIFICATE-----\\s+[A-Za-z0-9+/=\\s]+-----END CERTIFICATE-----\\s*)+");

    private TaskCertificates() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Validates and snapshots the authority before creating a container; no file means no trust.
     *
     * @param paths The account's paths.
     * @param state This task's private state directory.
     * @return Its copy, or {@code null} without a configured file.
     */
    public static @Nullable Path prepare(PoreloPaths paths, Path state) {
        final Path file = paths.taskCaCertificates();
        try {
            final PosixFileAttributes attributes;
            try {
                attributes =
                        Files.readAttributes(
                                file, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException ex) {
                final Path stale = state.resolve(FILE);
                try {
                    Files.deleteIfExists(stale);
                } catch (IOException failure) {
                    throw new Refused(
                            "cannot remove "
                                    + stale
                                    + " left by an earlier start; check the task's state directory permissions",
                            failure);
                }
                return null;
            }
            if (!attributes.isRegularFile()) {
                throw new Refused(
                        file
                                + " must be a regular file owned by this account; replace it with the host authority's PEM certificates and run chmod 600 "
                                + file);
            }
            if (!attributes.owner().equals(Files.getOwner(Path.of("/proc/self")))
                    || attributes.permissions().stream()
                            .anyMatch(permission -> !permission.name().startsWith("OWNER_"))) {
                throw new Refused(
                        file
                                + " must be owned by this account and accessible only to it; restore its ownership and run chmod 600 "
                                + file);
            }
            Files.createDirectories(state);
            Files.setPosixFilePermissions(state, PosixFilePermissions.fromString("rwx------"));
            final Path temporary =
                    Files.createTempFile(
                            state,
                            "task-ca-",
                            ".pem",
                            PosixFilePermissions.asFileAttribute(
                                    PosixFilePermissions.fromString("rw-------")));
            try {
                try (var channel =
                                Files.newByteChannel(
                                        file,
                                        Set.of(
                                                StandardOpenOption.READ,
                                                LinkOption.NOFOLLOW_LINKS));
                        var in = Channels.newInputStream(channel)) {
                    Files.copy(in, temporary, StandardCopyOption.REPLACE_EXISTING);
                }
                if (!java.util.Objects.equals(
                        attributes.fileKey(),
                        Files.readAttributes(
                                        file, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                                .fileKey())) {
                    throw new Refused(
                            file
                                    + " changed while reading its certificates; try starting the task again");
                }
                validate(temporary, file);
                // The agent is a subordinate uid: it needs read access to the mount, inside private
                // host state.
                Files.setPosixFilePermissions(
                        temporary, PosixFilePermissions.fromString("rw-r--r--"));
                final Path copy = state.resolve(FILE);
                Files.move(
                        temporary,
                        copy,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                return copy;
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException ex) {
            throw new Refused(
                    "cannot read the certificates in "
                            + file
                            + "; check its ownership and run chmod 600 "
                            + file,
                    ex);
        }
    }

    private static void validate(Path copy, Path source) throws IOException {
        try {
            if (!PEM.matcher(Files.readString(copy)).matches()) {
                throw new IllegalStateException("only PEM certificates are allowed");
            }
            TrustedCertificates.context(copy);
        } catch (IllegalStateException ex) {
            throw new Refused(
                    source
                            + " must contain one or more valid X.509 PEM certificates only; replace its contents with the host authority's certificates",
                    ex);
        }
    }

    /**
     * Updates the image's trust store in one root step, before any agent runs.
     *
     * @param runner Runs the root step.
     * @param podman Runtime recognizing the task's recorded id.
     * @param paths The account's paths.
     * @param container Task name.
     */
    public static void install(
            CommandRunner runner, Podman podman, PoreloPaths paths, String container) {
        if (!Files.exists(
                paths.tasks().containerState(container).resolve(FILE), LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        final String script =
                """
                PATH=/usr/local/sbin:/usr/sbin:/sbin:/usr/local/bin:/usr/bin:/bin
                if command -v update-ca-certificates >/dev/null 2>&1; then
                    mkdir -p /usr/local/share/ca-certificates
                    cp "$1" /usr/local/share/ca-certificates/porelo-host.crt
                    chmod 0644 /usr/local/share/ca-certificates/porelo-host.crt
                    update-ca-certificates
                elif command -v update-ca-trust >/dev/null 2>&1; then
                    mkdir -p /etc/pki/ca-trust/source/anchors
                    cp "$1" /etc/pki/ca-trust/source/anchors/porelo-host.pem
                    chmod 0644 /etc/pki/ca-trust/source/anchors/porelo-host.pem
                    update-ca-trust
                else
                    exit 69
                fi
                """;
        final org.fuin.porelo.core.process.CommandResult result;
        try {
            result =
                    runner.run(
                            Command.of(
                                    podman.asRootArguments(
                                            container,
                                            List.of(
                                                    "sh",
                                                    "-ec",
                                                    script,
                                                    "porelo-task-ca",
                                                    MOUNT))));
        } catch (CommandException ex) {
            podman.stop(container);
            throw new Refused(
                    paths.taskCaCertificates()
                            + ": cannot update the task's trust store; the root step could not run; check podman and the image",
                    ex);
        }
        if (!result.successful()) {
            podman.stop(container);
            throw new Refused(
                    paths.taskCaCertificates()
                            + ": cannot update the task's trust store; "
                            + (result.exitCode() == 69
                                    ? "the image has neither update-ca-certificates nor update-ca-trust; use an image with ca-certificates installed"
                                    : "the CA update command failed; repair the image before starting the task"));
        }
    }
}
