package no.sjakkportal.importer;

import jakarta.validation.constraints.Size;
import java.net.URI;
import no.sjakkportal.common.error.ChessException;
import no.sjakkportal.common.error.ErrorCode;
import no.sjakkportal.game.GameView;
import no.sjakkportal.security.CurrentUser;
import no.sjakkportal.security.RequestGuards;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ImportController {

    /** Exactly one of the two: a game link, or the PGN copied from the site. */
    record ImportRequest(@Size(max = 300) String url, @Size(max = 200_000) String pgn) {
    }

    private final ImportService importService;
    private final RequestGuards guards;

    public ImportController(ImportService importService, RequestGuards guards) {
        this.importService = importService;
        this.guards = guards;
    }

    @PostMapping("/api/games/import")
    ResponseEntity<GameView> importGame(@jakarta.validation.Valid @RequestBody ImportRequest body, @AuthenticationPrincipal CurrentUser user) {
        boolean hasUrl = body.url() != null && !body.url().isBlank();
        boolean hasPgn = body.pgn() != null && !body.pgn().isBlank();
        if (hasUrl == hasPgn) {
            throw new ChessException(ErrorCode.VALIDATION_FAILED, "Oppgi enten en lenke eller PGN-tekst.");
        }
        guards.limit(guards.limits().gameSubmissionPolicy(), "user:" + user.id());
        GameView game = importService.importGame(user, hasUrl ? body.url() : null, hasPgn ? body.pgn() : null);
        return ResponseEntity.created(URI.create("/api/games/" + game.id())).body(game);
    }
}
