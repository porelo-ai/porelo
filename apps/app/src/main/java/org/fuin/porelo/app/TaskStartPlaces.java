package org.fuin.porelo.app;

import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * Reads the role and place {@code porelo task start} was given and checks them as the daemon's
 * Start does, refusing by the same names: 64 for what was typed, 69 for what is unavailable here.
 */
final class TaskStartPlaces implements StartPlaces {

    private static final String PUSH = "push:";

    @Override
    public @Nullable RunPlace taken(
            final PoreloContext context,
            final Path projectFile,
            final String task,
            final @Nullable String repository,
            final boolean gated,
            final @Nullable String role,
            final @Nullable String place,
            final java.util.function.Supplier<@Nullable String> branch) {
        try {
            return RunPlaceCheck.of(context)
                    .taken(projectFile, task, repository, gated, role(role), place(place), branch);
        } catch (RunPlaceCheck.Refused refused) {
            final RunPlaceCheck.Refusal refusal = refused.refusal();
            if (RunPlaceCheck.INVALID_PARAMETER.equals(refusal.error())) {
                throw new Refused(
                        64, "--" + refusal.fields().get("parameter") + ": " + refusal.said());
            }
            throw new Refused(refusal.typed() ? 64 : 69, refusal.name() + ": " + refusal.said());
        }
    }

    private static @Nullable String role(final @Nullable String typed) {
        if (typed == null) {
            return null;
        }
        return switch (typed) {
            case "author" -> RunPlace.AUTHOR;
            case "reviewer" -> RunPlace.REVIEWER;
            default -> throw new Refused(64, "--role takes author or reviewer");
        };
    }

    private static RunPlaceCheck.@Nullable Place place(final @Nullable String typed) {
        if (typed == null) {
            return null;
        }
        if ("own".equals(typed)) {
            return new RunPlaceCheck.Place.OwnPullRequest();
        }
        if (typed.startsWith(PUSH)) {
            return new RunPlaceCheck.Place.GatePush(null, typed.substring(PUSH.length()));
        }
        if (typed.contains("://")) {
            return new RunPlaceCheck.Place.PullRequest(typed);
        }
        throw new Refused(64, "--place takes a pull request's https address, own or push:<name>");
    }
}
