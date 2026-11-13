package org.fuin.porelo.agent.api;

import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * One file an agent's image build fetches from the network.
 *
 * <p>Declared rather than left inside a shell fragment, so that two questions have answers without
 * reading a build log: <em>what exactly did this image install</em>, and <em>was it the thing the
 * publisher intended</em>. The reference implementation answers neither - it installs agent CLIs
 * with {@code curl -fsSL https://…/install.sh | bash}, re-resolved on every build.
 *
 * <p><strong>A digest is required unless the artifact says otherwise out loud.</strong> Some
 * vendors publish none, and pretending otherwise would be worse than admitting it: {@code
 * unverified} with a stated reason keeps the gap visible instead of making a uniform-looking file
 * that is only sometimes telling the truth.
 *
 * @param url Where to fetch it.
 * @param sha256 Expected digest, or {@code null} when {@code unverified}.
 * @param target Absolute path to install it at.
 * @param mode Octal file mode, for example {@code 0755}.
 * @param unverified Whether this artifact is knowingly unverifiable.
 * @param reason Why it is unverifiable; required when {@code unverified}.
 * @param license The license the publisher declares for this release - an SPDX id or, where it
 *     gives none, the name it uses - or {@code null} when nobody has recorded one.
 * @param owner Who the build step that installs it runs as: {@link Owner#ROOT} before the
 *     definition's {@code as_root} fragments, or {@link Owner#AGENT} before its {@code as_agent}
 *     ones.
 */
public record InstallArtifact(
        String url,
        @Nullable String sha256,
        String target,
        String mode,
        boolean unverified,
        @Nullable String reason,
        @Nullable String license,
        Owner owner) {

    /** The agent's home in every task image, which the agent's user owns. */
    private static final String AGENT_HOME = "/home/agent";

    /**
     * Who installs an artifact, written {@code owner:} in a definition.
     *
     * <p>An agent's binary installed by the agent can be replaced by the agent, so the next start
     * runs what the agent wrote; one a root step installs outside the agent's home cannot.
     */
    public enum Owner {

        /**
         * Installed as root, before the {@code as_root} fragments, so one of them can unpack it.
         */
        ROOT("root"),

        /** Installed as the agent's user, before the {@code as_agent} fragments; the default. */
        AGENT("agent");

        private final String word;

        Owner(final String word) {
            this.word = word;
        }

        /**
         * Returns the word a definition writes for this owner.
         *
         * @return {@code root} or {@code agent}.
         */
        public String word() {
            return word;
        }

        /**
         * Reads the word a definition writes.
         *
         * @param word {@code root} or {@code agent}; {@code null} for the default.
         * @return The owner, {@link #AGENT} when none is written.
         * @throws AgentException For any other word.
         */
        public static Owner of(final @Nullable String word) {
            if (word == null) {
                return AGENT;
            }
            for (final Owner owner : values()) {
                if (owner.word.equals(word)) {
                    return owner;
                }
            }
            throw new AgentException(
                    "An install artifact's owner must be 'root' or 'agent', not '"
                            + word.replaceAll("\\p{Cntrl}", "?")
                            + "'");
        }
    }

    /**
     * Constructor with all data.
     *
     * @param url Where to fetch it.
     * @param sha256 Expected digest, or {@code null} when unverified.
     * @param target Absolute path to install it at.
     * @param mode Octal file mode.
     * @param unverified Whether it is knowingly unverifiable.
     * @param reason Why; required when unverified.
     * @param license The declared license, or {@code null}.
     * @param owner Who installs it.
     */
    public InstallArtifact {
        if (license != null) {
            // Recorded in a bill and compared there: a newline or a quote would be a second value.
            requirePlain("Install artifact license", license);
        }
        if (!url.startsWith("https://")) {
            // Not a style preference: a plaintext fetch lets anyone on the path replace the
            // artifact, and the digest below is only worth having if the URL is worth trusting.
            throw new AgentException("Install artifact URL must be https: " + url);
        }
        if (!target.startsWith("/")) {
            throw new AgentException("Install target must be absolute: " + target);
        }
        // Both of these are interpolated into the generated build script, so a quote or a newline
        // in either is not a bad value - it is another line of the script, contributed by whatever
        // answered the agent protocol. The build already runs an agent's own install fragments, so
        // this is not a new capability for a trusted agent; it is refusing malformed data at the
        // boundary instead of discovering it as a build that does something else.
        requirePlain("Install target", target);
        // A path and nothing else: it goes into the build script unquoted, where ';' ran a second
        // command.
        if (!target.matches(PackagedTree.PATH)) {
            throw new AgentException(
                    "Install target must be a plain path - letters, digits, '.', '_', '-', '+' and"
                            + " '/': "
                            + target.replaceAll("\\p{Cntrl}", "?"));
        }
        if (owner == Owner.ROOT && inAgentsHome(target)) {
            // Root-owned in a directory the agent owns is not root-owned for long: the agent
            // renames it away and puts its own file in its place.
            throw new AgentException(
                    "An install artifact owned by root may not be under "
                            + AGENT_HOME
                            + ", which the agent can write to: "
                            + target);
        }
        if (reason != null && reason.chars().anyMatch(Character::isISOControl)) {
            // It becomes a comment line, and a line break would end the comment and start an
            // instruction.
            throw new AgentException("An install artifact's reason must be one line");
        }
        requirePlain("Install artifact URL", url);
        if (!mode.matches("0?[0-7]{3}")) {
            throw new AgentException("Install mode must be octal, for example 0755: " + mode);
        }
        if (unverified) {
            if (reason == null || reason.isBlank()) {
                throw new AgentException(
                        "Artifact "
                                + url
                                + " is marked unverified but gives no reason. State why the publisher"
                                + " offers no digest, so the gap is visible rather than habitual.");
            }
            if (sha256 != null) {
                throw new AgentException(
                        "Artifact "
                                + url
                                + " has a digest and is also marked unverified. Pick one.");
            }
        } else {
            if (sha256 == null || !sha256.matches("[a-f0-9]{64}")) {
                throw new AgentException(
                        "Artifact "
                                + url
                                + " needs a lower-case 64-character sha256, or unverified: true with a"
                                + " reason");
            }
        }
    }

    /**
     * Constructor without a license, as every definition was written before one was recorded.
     *
     * @param url Where to fetch it.
     * @param sha256 Expected digest, or {@code null} when unverified.
     * @param target Absolute path to install it at.
     * @param mode Octal file mode.
     * @param unverified Whether it is knowingly unverifiable.
     * @param reason Why; required when unverified.
     */
    public InstallArtifact(
            String url,
            @Nullable String sha256,
            String target,
            String mode,
            boolean unverified,
            @Nullable String reason) {
        this(url, sha256, target, mode, unverified, reason, null);
    }

    /**
     * Constructor for an artifact the agent's user installs, as every definition was written before
     * an owner was recorded.
     *
     * @param url Where to fetch it.
     * @param sha256 Expected digest, or {@code null} when unverified.
     * @param target Absolute path to install it at.
     * @param mode Octal file mode.
     * @param unverified Whether it is knowingly unverifiable.
     * @param reason Why; required when unverified.
     * @param license The declared license, or {@code null}.
     */
    public InstallArtifact(
            String url,
            @Nullable String sha256,
            String target,
            String mode,
            boolean unverified,
            @Nullable String reason,
            @Nullable String license) {
        this(url, sha256, target, mode, unverified, reason, license, Owner.AGENT);
    }

    /**
     * Tells whether this artifact is installed by a root step.
     *
     * @return {@code true} for {@link Owner#ROOT}.
     */
    public boolean rootOwned() {
        return owner == Owner.ROOT;
    }

    /**
     * Tells whether a target is the agent's home or below it, after resolving {@code .} and {@code
     * ..}.
     */
    private static boolean inAgentsHome(final String target) {
        return Path.of(target).normalize().startsWith(Path.of(AGENT_HOME));
    }

    /**
     * Returns the file name the artifact is installed as.
     *
     * @return Last path segment of the target.
     */
    public String fileName() {
        return target.substring(target.lastIndexOf('/') + 1);
    }

    /**
     * Refuses a value that would not survive being written into a script.
     *
     * @param what Which value it is, for the message.
     * @param value The value.
     */
    private static void requirePlain(String what, String value) {
        for (final char character : value.toCharArray()) {
            if (character < ' '
                    || character == '\''
                    || character == '"'
                    || character == '\\'
                    || character == '$'
                    || character == '`') {
                throw new AgentException(
                        what
                                + " may not contain quotes, backslashes, shell"
                                + " expansion characters or control characters: "
                                + value);
            }
        }
    }
}
