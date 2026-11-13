package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import org.fuin.porelo.ecosystem.api.Detection;
import org.fuin.porelo.ecosystem.api.EcosystemProtocol;
import org.fuin.porelo.ecosystem.api.EcosystemRefused;
import org.fuin.porelo.ecosystem.api.Finding;
import org.fuin.porelo.ecosystem.api.InstalledEcosystems;
import org.jspecify.annotations.Nullable;

/**
 * A plugin's {@code Resolve}, asked when a project file is made: its answer replaces what its own
 * {@code Detect} found, and when it cannot say or fails, what {@code Detect} found stands with a
 * note naming what was asked.
 *
 * <p>The person's proxy settings reach the plugin's process and nothing else: never a finding, a
 * note or an answer, since a proxy's address can hold a credential.
 */
public final class EcosystemLookup {

    /** The longest list of what was asked a note names. */
    private static final int ASKED = 400;

    private static final java.util.regex.Pattern PLAIN_KEY =
            java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]{0,99}");

    private static final java.util.regex.Pattern PLAIN_VALUE =
            java.util.regex.Pattern.compile("[A-Za-z0-9.+\\-\\[\\](),]{1,40}");

    private EcosystemLookup() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Returns the proxy settings of an environment.
     *
     * @param environment Reads a variable, {@code null} when unset.
     * @return Each of {@link EcosystemProtocol#PROXY_VARIABLES} set to a value that is not blank.
     */
    public static Map<String, String> proxies(final UnaryOperator<String> environment) {
        final Map<String, Object> read = new LinkedHashMap<>();
        for (final String name : EcosystemProtocol.PROXY_VARIABLES) {
            final @Nullable String value = environment.apply(name);
            if (value != null) {
                read.put(name, value);
            }
        }
        return proxies(read);
    }

    /**
     * Returns the proxy settings a client sent: only the names a plugin may have, as text.
     *
     * @param sent What the field held, or {@code null} when it was not sent.
     * @return Each of {@link EcosystemProtocol#PROXY_VARIABLES} it holds as text that is not blank.
     */
    public static Map<String, String> proxies(final @Nullable Object sent) {
        if (!(sent instanceof Map<?, ?> map)) {
            return Map.of();
        }
        final Map<String, String> found = new LinkedHashMap<>();
        for (final String name : EcosystemProtocol.PROXY_VARIABLES) {
            if (map.get(name) instanceof String value && !value.isBlank()) {
                found.put(name, value);
            }
        }
        return java.util.Collections.unmodifiableMap(found);
    }

    /**
     * Asks a plugin to look up what its own {@code Detect} answered.
     *
     * @param detector Who asks.
     * @param plugin The plugin.
     * @param checkout The directory it read.
     * @param home The home it read.
     * @param detected What its {@code Detect} answered.
     * @param proxies The person's proxy settings.
     * @return Its findings replaced by the answer, or as they were with a note.
     */
    public static Looked resolve(
            final EcosystemDetector detector,
            final InstalledEcosystems.Description plugin,
            final Path checkout,
            final Path home,
            final Detection detected,
            final Map<String, String> proxies) {
        if (detected.findings().isEmpty()) {
            return new Looked(detected, null);
        }
        final Optional<List<Finding>> answered;
        try {
            answered = detector.resolve(plugin, checkout, home, detected.findings(), proxies);
        } catch (EcosystemRefused ex) {
            return stands(plugin, detected, "it refused");
        } catch (IOException | RuntimeException ex) {
            // A reply that does not even parse lands here too, and its parser's message may quote
            // the reply, so nothing of the exception is said.
            // Never its words: a plugin's message may repeat what it was started with.
            return stands(
                    plugin,
                    detected,
                    "it failed or took longer than "
                            + EcosystemProtocol.RESOLVE_CALL_SECONDS
                            + " seconds");
        }
        if (answered.isEmpty()) {
            return stands(plugin, detected, "it cannot say");
        }
        if (holdsAProxy(answered.get(), proxies)) {
            return stands(plugin, detected, "its answer holds a proxy setting, so it is not used");
        }
        return new Looked(new Detection(answered.get(), detected.unplaced()), null);
    }

    /** What Detect found stands, with a note naming what was asked and why it stands. */
    private static Looked stands(
            final InstalledEcosystems.Description plugin,
            final Detection detected,
            final String why) {
        return new Looked(
                detected,
                plugin.name()
                        + " could not look up "
                        + asked(detected.findings())
                        + ": "
                        + why
                        + "; what it read stands");
    }

    /**
     * What a lookup left.
     *
     * @param detection The plugin's findings: the lookup's, or {@code Detect}'s.
     * @param note What a person should read when the lookup gave nothing, or {@code null}.
     */
    public record Looked(Detection detection, @Nullable String note) {}

    /**
     * What was asked: each tool by its key, and its value where it reads as a version. Nothing else
     * of a plugin's words, which are a stranger's input, and never a host or a credential's id.
     */
    private static String asked(final List<Finding> findings) {
        final String all =
                findings.stream()
                        .filter(each -> each.part() == Finding.Part.TOOLS && !each.unknown())
                        // A credential's line holds what a person's file keeps of it.
                        .filter(each -> !ProjectProposal.credential(each))
                        .filter(each -> PLAIN_KEY.matcher(each.key()).matches())
                        .map(
                                each ->
                                        PLAIN_VALUE.matcher(each.value()).matches()
                                                ? each.key() + " " + each.value()
                                                : each.key())
                        .collect(Collectors.joining(", "));
        if (all.isEmpty()) {
            return "what it read";
        }
        return all.length() > ASKED ? all.substring(0, ASKED) + "..." : all;
    }

    /**
     * Whether an answer repeats a proxy's address, which may hold a credential. A list of hosts not
     * to proxy holds none and is short enough to match an ordinary line, so it is not looked for.
     */
    private static boolean holdsAProxy(
            final List<Finding> findings, final Map<String, String> proxies) {
        final List<String> secret =
                proxies.entrySet().stream()
                        .filter(each -> !each.getKey().equalsIgnoreCase("NO_PROXY"))
                        .map(Map.Entry::getValue)
                        .toList();
        return findings.stream()
                .flatMap(
                        each ->
                                java.util.stream.Stream.of(
                                        each.key(), each.value(), each.file(), each.note()))
                .anyMatch(text -> secret.stream().anyMatch(text::contains));
    }
}
