package org.fuin.porelo.app;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.Supplier;
import org.fuin.porelo.gate.review.TaskParts;
import org.jspecify.annotations.Nullable;

/**
 * Reads the parts and place {@code porelo task start} was given and checks them as the daemon's
 * Start does, refusing by the same names: 64 for what was typed, 69 for what is unavailable here.
 */
final class TaskStartPlaces implements StartPlaces {

    private static final String PUSH = "push:";

    /** The options a contract member is typed as, where its name differs. */
    private static final Map<String, String> OPTIONS =
            Map.of("writeAs", "write-as", "readLanded", "read-landed", "rights", "right");

    @Override
    public StartParts taken(
            final PoreloContext context,
            final Path projectFile,
            final String task,
            final @Nullable String repository,
            final TaskParts.Asked asked,
            final boolean addedCredentials,
            final @Nullable String clearance,
            final @Nullable String place,
            final Supplier<@Nullable String> branch,
            final Supplier<@Nullable TaskParts> recorded) {
        try {
            return RunPlaceCheck.of(context)
                    .taken(
                            projectFile,
                            task,
                            repository,
                            asked,
                            addedCredentials,
                            clearance,
                            place(place),
                            branch,
                            recorded);
        } catch (RunPlaceCheck.Refused refused) {
            final RunPlaceCheck.Refusal refusal = refused.refusal();
            if (RunPlaceCheck.INVALID_PARAMETER.equals(refusal.error())) {
                final String parameter = String.valueOf(refusal.fields().get("parameter"));
                throw new Refused(
                        64,
                        "--" + OPTIONS.getOrDefault(parameter, parameter) + ": " + refusal.said());
            }
            throw new Refused(refusal.typed() ? 64 : 69, refusal.name() + ": " + refusal.said());
        }
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
