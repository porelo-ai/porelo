package org.fuin.porelo.agent.api;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The models an agent offers when a provider gives no list for the stored credential.
 *
 * @param providers The models per provider, preserving provider and model order.
 */
public record AgentModels(Map<String, List<Model>> providers) {

    /** An agent that declares no models. */
    public static final AgentModels NONE = new AgentModels(Map.of());

    /** Most models one provider's list may name. */
    public static final int MOST_MODELS = 100;

    /** Most characters of an id. */
    public static final int MOST_ID = 200;

    /** Most characters of a name. */
    public static final int MOST_NAME = 100;

    // What reaches a window or a terminal is plain text: no control, format or line characters.
    private static final java.util.regex.Pattern UNPLAIN =
            java.util.regex.Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]");

    private static void plain(final String text, final int most, final String where) {
        if (text.isBlank() || text.length() > most || UNPLAIN.matcher(text).find()) {
            throw new AgentException(
                    where
                            + " must be plain text of 1 to "
                            + most
                            + " characters, without control or format characters");
        }
    }

    /**
     * A model from the agent's own menu.
     *
     * @param id What Start's model takes.
     * @param name What the person sees.
     */
    public record Model(String id, String name) {

        /** Refuses absent fields before the adapter serializes its description. */
        public Model {
            if (id == null) {
                throw new AgentException("models.id must be text");
            }
            if (name == null) {
                throw new AgentException("models.name must be text");
            }
        }
    }

    /** Copies the lists and checks provider names and model ids. */
    public AgentModels {
        if (providers == null) {
            throw new AgentException("models must be a mapping");
        }
        final Map<String, List<Model>> copied = new LinkedHashMap<>();
        providers.forEach(
                (provider, models) -> {
                    final String where = "models." + provider;
                    if (provider == null || !provider.matches("[a-z0-9][a-z0-9-]{0,30}")) {
                        throw new AgentException(where + " is not a provider name");
                    }
                    if (models == null || models.isEmpty()) {
                        throw new AgentException(where + " must be a non-empty list");
                    }
                    if (models.size() > MOST_MODELS) {
                        throw new AgentException(
                                where + " names more than " + MOST_MODELS + " models");
                    }
                    final Set<String> ids = new HashSet<>();
                    for (final Model model : models) {
                        if (model == null) {
                            throw new AgentException(where + " model must be a mapping");
                        }
                        plain(model.id(), MOST_ID, where + ".id");
                        plain(model.name(), MOST_NAME, where + ".name");
                        if (model.id().isBlank() || !ids.add(model.id())) {
                            throw new AgentException(
                                    where
                                            + ".id must be non-empty and unique: '"
                                            + model.id()
                                            + "'");
                        }
                    }
                    copied.put(provider, List.copyOf(models));
                });
        providers = Collections.unmodifiableMap(copied);
    }
}
