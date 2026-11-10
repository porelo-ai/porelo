package org.fuin.porelo.app;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.runtime.ContainerName;
import org.fuin.porelo.vault.CardException;
import org.fuin.porelo.vault.PersonCard;
import org.fuin.porelo.vault.SigningKey;
import org.fuin.porelo.vault.SshCertificate;
import org.jspecify.annotations.Nullable;

/**
 * The keys of a task's messages: its own, which signs what it sends, and the task keys it believes
 * what arrives from.
 *
 * <p>A task signs with its own plain key, held by Porelo outside its container. The signature names
 * only that key, so the receiver looks up the key's certificate by its fingerprint - what this
 * machine kept at the task's start, else what the host it runs on keeps - and believes the message
 * when one of its own project's certifiers issued that certificate and it was valid when the
 * message arrived. Arrived means the moment the receiver's pass takes it, by the receiver's clock:
 * the sender sets the time a message says, and a key that is no longer vouched for could otherwise
 * sign with any date it likes. A task without a project believes the person who certified its own
 * key, as its commits are judged.
 */
public final class MessageSigners implements MessageDelivery.Signers {

    /** The project of a task that has none. */
    public static final String NO_PROJECT = "default";

    /** The key a task signs what it sends with. */
    @FunctionalInterface
    public interface Own {

        /**
         * Returns the key.
         *
         * @return The task's own key.
         * @throws Unsigned When it cannot sign now.
         */
        SigningKey key() throws Unsigned;
    }

    /** Looks up a task key's certificate by the key's fingerprint. */
    @FunctionalInterface
    public interface Certificates {

        /**
         * Returns a certificate.
         *
         * @param fingerprint The key's fingerprint, {@code SHA256:...}.
         * @return Its certificate, or {@code null} when none is kept.
         */
        @Nullable SshCertificate of(String fingerprint);
    }

    /** The keys whose certificates a task believes. */
    @FunctionalInterface
    public interface Certifiers {

        /**
         * Reads the keys.
         *
         * @return The keys, or {@code null} while they cannot be read now, as while the vault that
         *     holds them is locked.
         */
        @Nullable List<String> read();
    }

    /** Why a task's message cannot be signed. */
    public static final class Unsigned extends Exception {

        private static final long serialVersionUID = 1L;

        private final boolean waits;

        /**
         * Constructor.
         *
         * @param message Why, in words the agent is told.
         * @param waits Whether it may be signed later, so the message waits rather than being held.
         */
        public Unsigned(final String message, final boolean waits) {
            super(message);
            this.waits = waits;
        }

        /**
         * Returns whether it may be signed later.
         *
         * @return {@code true} while only the vault is locked.
         */
        public boolean waits() {
            return waits;
        }
    }

    /**
     * What an arriving message's key traced to.
     *
     * @param peer The peer it speaks for: the task's name in its own project, else its whole
     *     principal; "" when it is not believed.
     * @param principal The whole principal its certificate names, {@code task:<project>:<task>},
     *     which names it in any project; "" when it is not believed.
     * @param refusal Why it is not believed, naming the key, or "" when it is.
     * @param waits Whether it cannot be judged now, so the message waits where it arrived rather
     *     than being held.
     */
    public record Traced(String peer, String principal, String refusal, boolean waits) {

        /**
         * Constructor for a key that was judged, known by one name.
         *
         * @param peer The peer it speaks for, or "" when it is not believed.
         * @param refusal Why it is not believed, or "" when it is.
         */
        public Traced(final String peer, final String refusal) {
            this(peer, peer, refusal, false);
        }

        /**
         * Says whether a message's {@code metadata.from} names this sender: by the peer's name or
         * by its whole principal, so one name passes in its own project and in every other.
         *
         * @param from What the message says it is from.
         * @return {@code true} when it names this sender.
         */
        public boolean named(final String from) {
            return from.equals(peer) || !principal.isEmpty() && from.equals(principal);
        }

        /**
         * Returns whether the key is believed.
         *
         * @return {@code true} when nothing refused it.
         */
        public boolean believed() {
            return refusal.isEmpty();
        }

        private static Traced refused(final String why) {
            return new Traced("", why);
        }

        private static Traced waiting(final String why) {
            return new Traced("", "", why, true);
        }
    }

    private final Own own;

    private final Certificates certificates;

    private final String project;

    private final Certifiers certifiers;

    private @Nullable List<String> read;

    /**
     * Constructor.
     *
     * @param own The task's own key.
     * @param certificates Where a task key's certificate is looked up.
     * @param project The receiving task's project, or {@link #NO_PROJECT}.
     * @param certifiers The keys whose certificates the task believes, read once and only when
     *     something arrived: reading them reads the project's access commit.
     */
    public MessageSigners(
            final Own own,
            final Certificates certificates,
            final String project,
            final Certifiers certifiers) {
        this.own = own;
        this.certificates = certificates;
        this.project = project;
        this.certifiers = certifiers;
    }

    /**
     * Returns the keys of one task's messages on this machine.
     *
     * @param context The machine.
     * @param container The task's container.
     * @param project Its project, or {@code null} for a task without one; a task whose project file
     *     is gone takes {@link #believingNobody}.
     * @return Its keys.
     */
    public static MessageSigners of(
            final PoreloContext context, final String container, final @Nullable Project project) {
        final String name =
                project == null || NO_PROJECT.equals(project.name()) ? NO_PROJECT : project.name();
        return of(
                context,
                container,
                name,
                () -> {
                    if (project != null && !NO_PROJECT.equals(name)) {
                        return certifiers(context, project);
                    }
                    if (context.vault().exists() && context.opener().isEmpty()) {
                        // Its certifier is in the locked vault: what arrives waits, as what it
                        // sends does.
                        return null;
                    }
                    // A task without a project believes the person who certified its own key -
                    // for this task, never one of another project its container's name fits.
                    final TaskIdentity identity =
                            TaskRecipients.identity(context, container, NO_PROJECT);
                    if (identity == null) {
                        return List.of();
                    }
                    final List<String> principal =
                            List.of("task:" + NO_PROJECT + ":" + identity.task());
                    return new TaskSecrets(context)
                            .signing(container)
                            .map(signing -> SshCertificate.read(signing.certificate()))
                            .filter(certificate -> certificate.principals().equals(principal))
                            .map(certificate -> List.of(certificate.signer()))
                            .orElse(List.of());
                });
    }

    /**
     * Returns the keys of a task whose project's certifiers cannot be read, its file being gone or
     * unreadable, or its project unknown: it believes nobody, never a task without a project's
     * certifier.
     *
     * @param context The machine.
     * @param container The task's container.
     * @param project Its project's name, or {@code null} when it is not known.
     * @return Its keys, which refuse whatever arrives.
     */
    public static MessageSigners believingNobody(
            final PoreloContext context, final String container, final @Nullable String project) {
        return of(
                context,
                container,
                project == null ? "the unknown project of " + container : project,
                List::of);
    }

    private static MessageSigners of(
            final PoreloContext context,
            final String container,
            final String name,
            final Certifiers certifiers) {
        final TaskCertificateRecords records = new TaskCertificateRecords(context);
        final HostPerson host = new HostPerson(context);
        return new MessageSigners(
                () -> {
                    if (!context.vault().exists()) {
                        throw new Unsigned(
                                "there is no vault, so this task has no key of its own to sign it"
                                        + " with",
                                false);
                    }
                    if (context.opener().isEmpty()) {
                        throw new Unsigned(
                                "the vault is locked, and it holds this task's key", true);
                    }
                    final TaskSecrets.Signing signing =
                            new TaskSecrets(context)
                                    .signing(container)
                                    .orElseThrow(
                                            () ->
                                                    new Unsigned(
                                                            "this task has no certified key of its"
                                                                    + " own to sign it with",
                                                            false));
                    return new SigningKey(Base64.getDecoder().decode(signing.seed()), container);
                },
                fingerprint -> records.certificate(fingerprint, host),
                name,
                certifiers);
    }

    /**
     * Returns the keys allowed to certify a project's tasks.
     *
     * @param context The machine.
     * @param project The project.
     * @return Its members' current keys, empty for a project of this machine alone.
     */
    public static List<String> certifiers(final PoreloContext context, final Project project) {
        return ProjectMembers.certifiers(context, project.name());
    }

    /**
     * Returns the key the task signs with.
     *
     * @return Its key.
     * @throws Unsigned When it cannot sign now.
     */
    public SigningKey own() throws Unsigned {
        return own.key();
    }

    @Override
    public Traced trace(final byte[] key, final Instant arrived) {
        final String line = "ssh-ed25519 " + Base64.getEncoder().encodeToString(key);
        final String fingerprint;
        try {
            fingerprint = PersonCard.fingerprint(line);
        } catch (CardException ex) {
            return Traced.refused("it is signed by a key that is not a task's Ed25519 key");
        }
        final SshCertificate certificate;
        try {
            certificate = certificates.of(fingerprint);
        } catch (HostPerson.Refused ex) {
            return Traced.refused(
                    java.util.Objects.requireNonNull(
                            ex.getMessage(), "Host refusals have a diagnostic"));
        }
        if (certificate == null || !certificate.key().equals(line)) {
            return Traced.refused(
                    "it is signed by key " + fingerprint + ", and no certificate is kept for it");
        }
        final String principal =
                certificate.principals().size() == 1 ? certificate.principals().getFirst() : "";
        if (!oneTask(principal)) {
            return Traced.refused(
                    "it is signed by key " + fingerprint + ", which is certified for no one task");
        }
        final String[] parts = principal.split(":", -1);
        final List<String> trusted = certifiers();
        if (trusted == null) {
            return Traced.waiting(
                    "it is signed by key "
                            + fingerprint
                            + ", and the certifiers of "
                            + project
                            + " cannot be read while the vault is locked");
        }
        if (!trusted.contains(certificate.signer())) {
            return Traced.refused(
                    "it is signed by key "
                            + fingerprint
                            + ", certified by "
                            + PersonCard.fingerprint(certificate.signer())
                            + ", which is no certifier of "
                            + project);
        }
        if (!certificate.validAt(arrived)) {
            return Traced.refused(
                    "it is signed by key "
                            + fingerprint
                            + ", whose certificate is valid from "
                            + certificate.validAfter()
                            + " to "
                            + certificate.validBefore()
                            + ", and it arrived at "
                            + arrived);
        }
        return new Traced(parts[1].equals(project) ? parts[2] : principal, principal, "", false);
    }

    /**
     * Says whether a key is a task key whose certificate is kept, as the record's walk asks of a
     * signature it finds: whether it was believed is what its arrival decided.
     *
     * @param key The key's blob.
     * @return {@code true} when its certificate is kept.
     */
    public boolean certified(final byte[] key) {
        final String line = "ssh-ed25519 " + Base64.getEncoder().encodeToString(key);
        try {
            final SshCertificate certificate = certificates.of(PersonCard.fingerprint(line));
            return certificate != null && certificate.key().equals(line);
        } catch (CardException | HostPerson.Refused ex) {
            return false;
        }
    }

    private @Nullable List<String> certifiers() {
        if (read == null) {
            final List<String> now = certifiers.read();
            // Not kept while it cannot be read: the next message may find the vault open.
            read = now == null ? null : List.copyOf(now);
        }
        return read;
    }

    /**
     * Says whether a certificate's principal names exactly one task, {@code task:<project>:<task>},
     * each part a name such a task could have: a pattern, an empty part, a reserved peer or a
     * further colon would let one certificate pass as another sender.
     */
    private static boolean oneTask(final String principal) {
        final String[] parts = principal.split(":", -1);
        return parts.length == 3
                && "task".equals(parts[0])
                && PROJECT_NAME.matcher(parts[1]).matches()
                && ContainerName.refusal(parts[1], parts[2]).isEmpty();
    }

    /** A project's name, as a project file may give it. */
    private static final java.util.regex.Pattern PROJECT_NAME =
            java.util.regex.Pattern.compile(Project.NAME_PATTERN);
}
