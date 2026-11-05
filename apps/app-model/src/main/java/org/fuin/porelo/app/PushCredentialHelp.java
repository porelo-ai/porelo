package org.fuin.porelo.app;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.credential.Credential;
import org.fuin.porelo.core.credential.CredentialRegistry;
import org.fuin.porelo.core.process.Command;

/** One identity's push trial and the explanation shared by terminal and daemon. */
public final class PushCredentialHelp {
    private PushCredentialHelp() {}

    /** Describes a remote refusal using known classifications, never raw server output. */
    public static String failure(PoreloContext context, String url, String diagnostic) {
        return failure(context, url, diagnostic, context.credentialRegistry().forUrl("git", url));
    }

    /** Explains the credential actually offered, including an unsaved trial identity. */
    static String failure(
            PoreloContext context,
            String url,
            String diagnostic,
            @org.jspecify.annotations.Nullable Credential declared) {
        final String identity =
                declared == null
                        ? "no credential is declared; SSH chose by itself"
                        : declared.source().name().toLowerCase(java.util.Locale.ROOT)
                                + " "
                                + (declared.source() == Credential.Source.AGENT
                                        ? fingerprint(context, declared)
                                        : declared.id()
                                                + " ("
                                                + fingerprint(context, declared)
                                                + ")");
        final String forge =
                diagnostic.contains("denied to deploy key")
                        ? "The forge calls it a deploy key. "
                        : diagnostic.contains("Permission denied (publickey)")
                                ? "The forge refused the SSH key. "
                                : "The remote command failed; its cause has not been identified. ";
        return "The push was refused. Credential: "
                + identity
                + ". "
                + forge
                + "Run 'porelo credentials keys' to choose a key. A key missing from the list? Open KeePassXC, unlock it, and refresh.";
    }

    /** Names a forwarding refusal and gives a client the next command as arguments. */
    static PushRefused refused(
            PoreloContext context,
            String url,
            @org.jspecify.annotations.Nullable Credential credential,
            String outcome,
            org.fuin.porelo.core.process.@org.jspecify.annotations.Nullable CommandException
                    failed) {
        final List<String> command = new ArrayList<>(List.of("porelo", "credentials", "keys"));
        if (failed != null
                && credential != null
                && (credential.source() == Credential.Source.AGENT
                        || credential.source() == Credential.Source.FILE)) {
            final List<String> args = failed.getCommand().arguments();
            final int gitDir = args.indexOf("--git-dir");
            final int where = gitDir;
            if (where >= 0 && where + 1 < args.size() && args.contains("push")) {
                command.clear();
                command.addAll(
                        List.of(
                                "porelo",
                                "credentials",
                                "try-push",
                                url,
                                "--repository",
                                args.get(where + 1),
                                "--refspec",
                                args.getLast()));
                command.addAll(
                        credential.source() == Credential.Source.AGENT
                                ? List.of("--agent", "--key", credential.id())
                                : List.of("--file", credential.id()));
            }
        }
        final String diagnostic = failed == null ? "" : failed.standardError();
        final String message =
                outcome.equals("NO_CREDENTIAL")
                        ? "No single SSH credential is declared for "
                                + url
                                + "; the push was not attempted."
                        : outcome.equals("KEY_UNAVAILABLE")
                                ? "The declared SSH credential cannot be used; the push was not attempted."
                                : failure(context, url, diagnostic, credential);
        final String explanation =
                message
                        + " Run: "
                        + command.stream()
                                .map(PushCredentialHelp::word)
                                .collect(java.util.stream.Collectors.joining(" "));
        final Map<String, Object> details =
                Map.of(
                        "outcome",
                        outcome,
                        "address",
                        url,
                        "source",
                        credential == null ? "" : credential.source().name(),
                        "id",
                        credential == null ? "" : credential.id(),
                        "fingerprint",
                        credential == null ? "" : fingerprint(context, credential),
                        "forge",
                        diagnostic.contains("denied to deploy key") ? "DEPLOY_KEY" : "UNKNOWN",
                        "command",
                        List.copyOf(command),
                        "message",
                        explanation);
        return new PushRefused(explanation, details);
    }

    private static String word(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static String fingerprint(PoreloContext context, Credential credential) {
        if (credential.source() == Credential.Source.AGENT) {
            return credential.id().startsWith("SHA256:")
                    ? credential.id()
                    : AgentPublicKeys.fingerprint(credential.id());
        }
        if (credential.source() == Credential.Source.FILE) {
            try {
                final Path publicHalf = Path.of(credential.id() + ".pub");
                if (java.nio.file.Files.isReadable(publicHalf)) {
                    return AgentPublicKeys.fingerprint(java.nio.file.Files.readString(publicHalf));
                }
            } catch (java.io.IOException ex) {
                return "fingerprint unavailable";
            }
        }
        if (credential.source() == Credential.Source.VAULT) {
            final var held = context.readableCredentials().orElseGet(Map::of).get(credential.id());
            if (held != null) {
                try {
                    return AgentPublicKeys.fingerprint(
                            org.fuin.porelo.vault.StoredKey.of(held.value(), credential.id())
                                    .authorizedKeysLine());
                } catch (org.fuin.porelo.vault.VaultException | IllegalArgumentException ex) {
                    return "fingerprint unavailable";
                }
            }
        }
        return "fingerprint unavailable";
    }

    /** Tests one SSH identity with a dry run, without changing a declaration or a remote ref. */
    public static Map<String, Object> tryPush(
            PoreloContext context,
            Path repository,
            String url,
            String refspec,
            boolean agent,
            String key) {
        if (GitCredentialNames.kindOf(url) != GitCredentialNames.Kind.KEY
                || !GitCredentialNames.namesOneHost(url)
                || !refspec.matches(
                        "[A-Za-z0-9][A-Za-z0-9._/-]*:refs/heads/[A-Za-z0-9][A-Za-z0-9._/-]*")
                || refspec.contains("..")) {
            throw new org.fuin.porelo.core.credential.CredentialException(
                    "Name one SSH repository and a source:refs/heads/branch refspec, without force or wildcards.");
        }
        final String selected = agent ? AgentPublicKeys.select(context, key) : key;
        final Credential offered =
                new Credential(
                        selected,
                        Credential.Kind.SSH_KEY,
                        CredentialRegistry.normalise(url),
                        null,
                        "git",
                        agent ? Credential.Source.AGENT : Credential.Source.FILE);
        CredentialDeclarations.checkPossible(offered);
        try (FollowCredential lease =
                FollowCredential.open(context, url, "push-trial", "git", offered)) {
            if (!lease.lent())
                throw new org.fuin.porelo.core.credential.CredentialException(
                        "The selected key cannot be used.");
            final List<String> args =
                    new ArrayList<>(List.of("git", "-c", "core.hooksPath=/dev/null"));
            args.addAll(lease.arguments());
            args.addAll(
                    List.of(
                            "-C",
                            repository.toString(),
                            "push",
                            "--dry-run",
                            "--no-verify",
                            "--end-of-options",
                            url,
                            refspec));
            final var result =
                    context.runner().run(Command.of(args).withEnvironment(lease.environment()));
            return Map.of(
                    "accepted",
                    result.successful(),
                    "fingerprint",
                    fingerprint(context, offered),
                    "message",
                    result.successful()
                            ? "The dry run succeeded; no work was pushed and no credential was saved."
                            : "The dry run was refused. "
                                    + failure(context, url, result.standardError(), offered));
        }
    }
}
