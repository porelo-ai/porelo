package org.fuin.porelo.agent.impl.stub;

import org.fuin.porelo.agent.api.Agent;
import org.fuin.porelo.agent.api.AgentDefinition;
import org.fuin.porelo.agent.api.AgentDefinitionReader;
import org.fuin.porelo.agent.api.AgentException;
import org.fuin.porelo.agent.api.AgentMain;

/**
 * The agent the acceptance suite drives.
 *
 * <p>It exists so the suite keeps working once the real agents live in their own repositories.
 * Nothing around it is faked: a real image is built, a real container runs, and the firewall and
 * credential proxy are the ones Porelo ships. Only the tool inside is ours - a short script the
 * definition writes into the image, which asks for one name it is granted and one it is refused,
 * and redeems its task token through the proxy.
 *
 * <p>That is a stronger test than a vendor CLI for the parts the core owns, because those reaches
 * are deliberate rather than a side effect of whatever the vendor shipped that month. What it
 * cannot tell you is whether a real CLI honors a socket or stays inside its declared domains - that
 * belongs to the agent's own repository.
 *
 * <p>One override, and only the one every agent with a login has: where its login leaves the
 * credential. The agent API reads that in code, not data. Everything else is {@code stub.yaml},
 * which is the point: if this agent needed behavior Porelo could not express as data, the suite
 * would be testing the exception rather than the rule.
 */
public final class StubAgent implements Agent {

    /** Where {@code stub.yaml} writes the tool; {@code binary} in the definition names it. */
    static final String TARGET = "/usr/local/bin/porelo-stub-cli";

    /**
     * The file name a copy of this binary is installed under to be an agent that declares no
     * session id - the same stub otherwise - so a suite can prove what Porelo says of an agent that
     * cannot continue a conversation.
     */
    static final String SESSIONLESS = "porelo-agent-stub-sessionless";

    /** The name that copy's agent has. */
    static final String SESSIONLESS_NAME = "stub-sessionless";

    private static final String RESOURCE = "/agent/stub.yaml";

    private final AgentDefinition definition;

    /** Constructor. */
    public StubAgent() {
        this(false);
    }

    private StubAgent(final boolean sessionless) {
        this.definition =
                sessionless
                        ? sessionless()
                        : AgentDefinitionReader.fromResource(StubAgent.class, RESOURCE);
        if (!definition.name().equals(sessionless ? SESSIONLESS_NAME : "stub")) {
            throw new AgentException(
                    "the stub's definition names itself '" + definition.name() + "'");
        }
    }

    /**
     * Returns the agent a binary of this file name is.
     *
     * @param fileName The binary's own file name.
     * @return The stub, or the stub that declares no session id under {@link #SESSIONLESS}.
     */
    static StubAgent forExecutable(final String fileName) {
        return new StubAgent(SESSIONLESS.equals(fileName));
    }

    /**
     * Entry point of the {@code porelo-agent-stub} binary.
     *
     * @param args Command line arguments.
     */
    public static void main(String[] args) {
        AgentMain.run(forExecutable(executableName()), args);
    }

    /**
     * The running binary's own file name: a copy is a file of its own, so its name is not the
     * original's.
     */
    private static String executableName() {
        try {
            return java.nio.file.Path.of("/proc/self/exe").toRealPath().getFileName().toString();
        } catch (java.io.IOException ex) {
            throw new AgentException("cannot read which binary runs: " + ex.getMessage(), ex);
        }
    }

    @Override
    public AgentDefinition definition() {
        return definition;
    }

    @Override
    public String toString() {
        return definition.name();
    }

    /**
     * Reads the stub's definition without its session id, and under its own name.
     *
     * <p>From the one file rather than a second copy of it, which would drift from the script it
     * installs.
     */
    private static AgentDefinition sessionless() {
        final String yaml;
        try (java.io.InputStream in = StubAgent.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new AgentException("No agent definition at " + RESOURCE);
            }
            yaml = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException ex) {
            throw new AgentException("Cannot read " + RESOURCE, ex);
        }
        final String without = withoutSessionIds(yaml);
        return AgentDefinitionReader.read(
                new java.io.StringReader(without), RESOURCE + " without session_id");
    }

    /**
     * Drops {@code session.session_id} and renames the agent, refusing a definition whose shape no
     * longer has them where this looks.
     *
     * @param yaml The stub's definition.
     * @return The same definition without its session id, named {@link #SESSIONLESS_NAME}.
     */
    static String withoutSessionIds(final String yaml) {
        final StringBuilder kept = new StringBuilder();
        boolean inIds = false;
        boolean dropped = false;
        boolean renamed = false;
        for (final String line : yaml.split("\n", -1)) {
            if (line.equals("    session_id:")) {
                inIds = true;
                dropped = true;
                continue;
            }
            if (inIds && line.startsWith("        ")) {
                continue;
            }
            inIds = false;
            if (line.equals("name: stub")) {
                kept.append("name: ").append(SESSIONLESS_NAME).append('\n');
                renamed = true;
            } else if (line.startsWith("label: ")) {
                kept.append("label: Stub Agent without session ids\n");
            } else {
                kept.append(line).append('\n');
            }
        }
        if (!dropped || !renamed) {
            throw new AgentException(
                    "the stub's definition has no 'session_id' block or 'name: stub' line to change");
        }
        return kept.toString();
    }

    /**
     * Reads what {@code porelo-stub-cli login} writes: an {@code oauth} token in a JSON file with
     * what renews it beside it - the shape a subscription login leaves - so that the type a real
     * login stores, and the attributes it hands over, are measured too and not only an API key.
     *
     * @return The extractor.
     */
    @Override
    public org.fuin.porelo.agent.api.CredentialExtractor credentialExtractor() {
        final org.fuin.porelo.agent.api.CredentialExtractor token = field("accessToken");
        return directory ->
                token.extract(directory)
                        .map(
                                found -> {
                                    final java.util.Map<String, String> attributes =
                                            new java.util.LinkedHashMap<>();
                                    for (final String name :
                                            java.util.List.of(
                                                    org.fuin.porelo.agent.api.Credential
                                                            .REFRESH_TOKEN,
                                                    org.fuin.porelo.agent.api.Credential.EXPIRES_AT,
                                                    org.fuin.porelo.agent.api.Credential.TOKEN_URL,
                                                    org.fuin.porelo.agent.api.Credential
                                                            .CLIENT_ID)) {
                                        field(name)
                                                .extract(directory)
                                                .ifPresent(
                                                        value ->
                                                                attributes.put(
                                                                        name, value.secret()));
                                    }
                                    return new org.fuin.porelo.agent.api.Credential(
                                            found.type(), found.secret(), attributes);
                                });
    }

    private static org.fuin.porelo.agent.api.CredentialExtractor field(final String name) {
        return new org.fuin.porelo.agent.api.JsonFieldExtractor(
                ".credentials.json", "stubOauth." + name, "oauth");
    }

    /**
     * Reads the end of the stub's run: its last act is one call to its provider through the broker,
     * and the answer it printed says whether the provider refused it.
     *
     * @param line One line of its output.
     * @return How its run ended, or {@code null} for a line that is not the provider's answer.
     */
    @Override
    public org.fuin.porelo.agent.api.@org.jspecify.annotations.Nullable AgentEnd ended(
            String line) {
        if (!line.startsWith("{")) {
            return null;
        }
        final Object parsed;
        try {
            parsed = org.fuin.porelo.wire.Json.parse(line);
        } catch (RuntimeException ex) {
            return null;
        }
        if (!(parsed instanceof java.util.Map<?, ?> answer)
                || !(answer.get("type") instanceof String type)) {
            return null;
        }
        if (type.equals("error")) {
            final String message =
                    answer.get("error") instanceof java.util.Map<?, ?> error
                                    && error.get("message") instanceof String text
                            ? text
                            : "the provider refused";
            return new org.fuin.porelo.agent.api.AgentEnd(
                    false, message, org.fuin.porelo.agent.api.AgentEnd.PROVIDER, null);
        }
        return type.equals("message")
                ? new org.fuin.porelo.agent.api.AgentEnd(
                        true, "", org.fuin.porelo.agent.api.AgentEnd.AGENT, null)
                : null;
    }
}
