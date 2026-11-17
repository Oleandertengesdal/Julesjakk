package no.sjakkportal.importer;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognises game links from Lichess and Chess.com and extracts the game id. The importer only ever
 * requests URLs it builds itself from such an id, never the address the user typed, so a crafted link
 * cannot make the server fetch an internal or arbitrary address.
 */
public final class ExternalLinks {

    private static final Pattern LICHESS_PATH = Pattern.compile("/([A-Za-z0-9]{8})(?:[A-Za-z0-9]{4})?(?:/(?:white|black))?/?");
    private static final Pattern CHESS_COM_PATH = Pattern.compile("/(?:game/(live|daily)|(live|daily)/game)/([0-9]{1,20})/?");

    private ExternalLinks() {
    }

    public static Optional<ExternalGameRef> parse(String url) {
        if (url == null || url.length() > 300) {
            return Optional.empty();
        }
        URI uri;
        try {
            uri = new URI(url.strip());
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        String path = uri.getPath() == null ? "" : uri.getPath();
        if (!scheme.equals("https") && !scheme.equals("http") || uri.getUserInfo() != null || uri.getPort() != -1) {
            return Optional.empty();
        }
        if (host.equals("lichess.org") || host.equals("www.lichess.org")) {
            Matcher matcher = LICHESS_PATH.matcher(path);
            return matcher.matches() ? Optional.of(new ExternalGameRef("LICHESS", matcher.group(1))) : Optional.empty();
        }
        if (host.equals("chess.com") || host.equals("www.chess.com")) {
            Matcher matcher = CHESS_COM_PATH.matcher(path);
            if (!matcher.matches()) {
                return Optional.empty();
            }
            String kind = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            String id = matcher.group(3);
            return Optional.of(new ExternalGameRef("CHESS_COM", kind.equals("daily") ? "daily:" + id : id));
        }
        return Optional.empty();
    }
}
