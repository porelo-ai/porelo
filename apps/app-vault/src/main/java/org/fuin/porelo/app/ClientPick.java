package org.fuin.porelo.app;

import java.net.http.HttpClient;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.fuin.porelo.agent.api.Credential;
import org.fuin.porelo.supervisor.TokenPurchase;
import org.jspecify.annotations.Nullable;

/**
 * Picks the OAuth client a sign-in was granted to, where the agent's CLI holds several.
 *
 * <p><strong>Why the service is asked.</strong> An agent's CLI may hold several OAuth clients, each
 * an id and a secret, and choose one by the kind of sign-in in its code; nothing in its files says
 * which one a sign-in went to. A wrong pair is refused at the first renewal, an hour after a
 * sign-in that looked whole. So the host tries each id with each secret once, right after the
 * sign-in, with the fresh refresh token, and keeps the pair the service answers with a token: on
 * the host, never in a task, each pair at most once.
 */
final class ClientPick {

    /** The OAuth error code in a refusal's text, which names the status and the code. */
    private static final Pattern CODE = Pattern.compile("\\((\\d{3}), ([a-z_]+)\\)");

    /**
     * The codes a wrong pair is answered with: no such client, or a client this sign-in's token was
     * not granted to. Any other answer is the service not judging the pair at all.
     */
    private static final Set<String> WRONG_PAIR =
            Set.of("invalid_client", "unauthorized_client", "invalid_grant");

    private ClientPick() {
        throw new UnsupportedOperationException("Utility class");
    }

    /** No pair was answered with a token. */
    static final class NoPair extends RuntimeException {

        private static final long serialVersionUID = 1L;

        NoPair(final String message) {
            super(message);
        }
    }

    /**
     * Returns the credential with the client its service answers, the candidate lists gone.
     *
     * <p>A credential with no candidate lists is returned as it is. With them, each id is tried
     * with each secret, ids outer, in list order, until one answers with a token; that answer's
     * access token is kept, and a rotated refresh token replaces the one it was asked with. A wrong
     * pair answers {@code invalid_client} (no such client), or {@code unauthorized_client} or
     * {@code invalid_grant} (a client, but the token is not its own): each means the next pair.
     *
     * @param value What the agent's sign-in left.
     * @param http How the authorization server is reached.
     * @param clock What time it is.
     * @return The credential to keep.
     * @throws NoPair If no pair is answered with a token, naming the counts and error codes only.
     */
    static Credential pick(final Credential value, final HttpClient http, final Clock clock) {
        final String ids = value.attributes().get(Credential.CLIENT_ID_CANDIDATES);
        final String secrets = value.attributes().get(Credential.CLIENT_SECRET_CANDIDATES);
        if (ids == null && secrets == null) {
            return value;
        }
        final Map<String, String> attributes = new LinkedHashMap<>(value.attributes());
        attributes.remove(Credential.CLIENT_ID_CANDIDATES);
        attributes.remove(Credential.CLIENT_SECRET_CANDIDATES);
        final String url = attributes.get(Credential.TOKEN_URL);
        final String refresh = attributes.get(Credential.REFRESH_TOKEN);
        final List<String> idList = lines(ids, attributes.get(Credential.CLIENT_ID));
        final List<String> secretList = lines(secrets, attributes.get(Credential.CLIENT_SECRET));
        if (url == null
                || refresh == null
                || idList.isEmpty()
                || secretList.isEmpty()
                || !org.fuin.porelo.supervisor.Grants.secure(url)) {
            // Nothing to ask with, or nowhere a refresh token may be sent: kept with no client, so
            // it is said not renewable rather than renewed with a guess.
            attributes.remove(Credential.CLIENT_ID);
            attributes.remove(Credential.CLIENT_SECRET);
            return new Credential(value.type(), value.secret(), attributes);
        }
        final Set<String> codes = new LinkedHashSet<>();
        for (final String id : idList) {
            for (final String secret : secretList) {
                final String[] rotated = {refresh};
                final TokenPurchase purchase =
                        new TokenPurchase(
                                new TokenPurchase.Refresh(
                                        url, id, secret, refresh, next -> rotated[0] = next),
                                http,
                                clock);
                final String token;
                try {
                    token = purchase.current();
                } catch (final TokenPurchase.Ended ex) {
                    // invalid_grant: a client, but this sign-in's token is not its own.
                    codes.add("400 invalid_grant");
                    continue;
                } catch (final TokenPurchase.Refused ex) {
                    final Matcher code = CODE.matcher(String.valueOf(ex.getMessage()));
                    if (!code.find() || !WRONG_PAIR.contains(code.group(2))) {
                        // Not the service saying no to a pair - unreachable, busy, or an answer
                        // that
                        // is no such refusal: trying more pairs would only say the same. Its text
                        // is
                        // never passed on, since a transport's own message may hold what was sent.
                        throw new NoPair(
                                "the client could not be matched: the token endpoint "
                                        + (code.find(0)
                                                ? "answered " + code.group(1) + " " + code.group(2)
                                                : "could not be asked"));
                    }
                    codes.add(code.group(1) + " " + code.group(2));
                    continue;
                } catch (final RuntimeException ex) {
                    throw new NoPair(
                            "the client could not be matched: the token endpoint could not be asked");
                }
                attributes.put(Credential.CLIENT_ID, id);
                attributes.put(Credential.CLIENT_SECRET, secret);
                attributes.put(Credential.REFRESH_TOKEN, rotated[0]);
                attributes.put(
                        Credential.EXPIRES_AT,
                        Long.toString(
                                purchase.goodUntil().plus(TokenPurchase.MARGIN).toEpochMilli()));
                return new Credential(value.type(), token, attributes);
            }
        }
        throw new NoPair(
                "none of the "
                        + idList.size() * secretList.size()
                        + " clients the agent's CLI holds ("
                        + idList.size()
                        + " ids, "
                        + secretList.size()
                        + " secrets) answered this sign-in with a token ("
                        + String.join(", ", codes)
                        + "); the sign-in could not be kept renewable");
    }

    /** The candidates, distinct, none blank; a single value where no list was given. */
    private static List<String> lines(
            final @Nullable String listed, final @Nullable String single) {
        final List<String> found = new ArrayList<>();
        final String source = listed != null ? listed : single == null ? "" : single;
        for (final String line : source.split("\n", -1)) {
            final String candidate = line.strip();
            if (!candidate.isEmpty() && !found.contains(candidate)) {
                found.add(candidate);
            }
        }
        return found;
    }
}
