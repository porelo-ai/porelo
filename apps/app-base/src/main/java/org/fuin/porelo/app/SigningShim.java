package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * The program a certified task's git signs with in place of {@code ssh-keygen}: it sends what git
 * would sign to the task's gate, which holds the task's key on the host, and writes back the gate's
 * signature. Anything else is {@code ssh-keygen}'s own, and a signature in another namespace than
 * git's is refused.
 */
public final class SigningShim {

    /** Where the program is in the container. */
    public static final String MOUNT = "/run/porelo/sign";

    /** Its file in the task's runtime state, written by the launch of a certified task. */
    public static final String FILE = "sign";

    /** The program, a POSIX shell script: every image has a shell, curl and git. */
    public static final String TEXT =
            """
            #!/bin/sh
            # Porelo: signs this task's commit at its gate; the task's key never enters the container.
            case "$1 $2" in
            "-Y sign") ;;
            *) exec ssh-keygen "$@" ;;
            esac
            namespace=""
            file=""
            while [ $# -gt 0 ]; do
              case "$1" in
              -n) namespace="$2"; shift 2 ;;
              -f) shift 2 ;;
              -Y|-U) [ "$1" = -Y ] && shift 2 || shift ;;
              *) file="$1"; shift ;;
              esac
            done
            if [ "$namespace" != git ] || [ -z "$file" ]; then
              echo "porelo: this task's key signs commits only" >&2
              exit 1
            fi
            header=$(git config --get "http.${PORELO_GATE_URL}.extraHeader") || exit 1
            exec curl --silent --show-error --fail --max-time 60 -H "$header" \\
              --data-binary "@$file" -o "$file.sig" "${PORELO_GATE_URL}porelo/sign"
            """;

    private SigningShim() {}

    /**
     * Writes the program into a task's runtime state, readable and runnable, not writable.
     *
     * @param state The task's runtime state.
     * @return The file.
     * @throws IOException If it cannot be written.
     */
    public static Path prepare(final Path state) throws IOException {
        final Path file = state.resolve(FILE);
        Files.deleteIfExists(file);
        Files.writeString(file, TEXT, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r-xr-xr-x"));
        return file;
    }
}
