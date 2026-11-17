package no.sjakkportal.game;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import no.sjakkportal.chess.Termination;
import no.sjakkportal.common.error.ChessException;
import no.sjakkportal.common.error.ErrorCode;
import no.sjakkportal.game.AnalysisService.AnalysisPayload;
import no.sjakkportal.game.AnalysisService.AnalysisView;
import no.sjakkportal.rating.GameResult;
import no.sjakkportal.rating.TimeCategory;
import no.sjakkportal.security.CurrentUser;
import no.sjakkportal.security.RequestGuards;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GameController {

    record SubmitRequest(@NotNull Long whiteId, @NotNull Long blackId, @NotNull GameResult result,
                         @Size(max = 200_000) String pgn, Termination termination, @PastOrPresent Instant playedAt,
                         Boolean rated, @Size(max = 5000) String comment, TimeCategory category,
                         @Size(max = 32) String timeControl) {
    }

    record LiveRequest(@NotNull Long whiteId, @NotNull Long blackId, Boolean rated, TimeCategory category,
                       @Size(max = 32) String timeControl) {
    }

    record MoveRequest(@NotBlank @Size(max = 100) String move, @NotNull Integer expectedPly) {
    }

    record FinishRequest(@NotNull GameResult result, Termination termination) {
    }

    record RejectRequest(@Size(max = 10_000) String reason) {
    }

    private final GameService gameService;
    private final AnalysisService analysisService;
    private final RequestGuards guards;

    public GameController(GameService gameService, AnalysisService analysisService, RequestGuards guards) {
        this.gameService = gameService;
        this.analysisService = analysisService;
        this.guards = guards;
    }

    /**
     * Registers a finished game. With an {@code Idempotency-Key} header (a UUID), repeating the request
     * returns the game created the first time instead of creating another one.
     */
    @PostMapping("/api/games")
    ResponseEntity<GameView> submit(@Valid @RequestBody SubmitRequest body,
                                    @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
                                    @AuthenticationPrincipal CurrentUser user) {
        guards.limit(guards.limits().gameSubmissionPolicy(), "user:" + user.id());
        UUID key = parseKey(idempotencyKey);
        if (key != null) {
            var existing = gameService.findByIdempotencyKey(user.id(), key);
            if (existing.isPresent()) {
                return ResponseEntity.ok(existing.get());
            }
        }
        GameService.SubmitCommand command = new GameService.SubmitCommand(body.whiteId(), body.blackId(), body.result(),
                body.pgn(), body.termination(), body.playedAt(), body.rated(), body.comment(), body.category(),
                body.timeControl(), key);
        try {
            GameView game = gameService.submit(user, command);
            return ResponseEntity.created(URI.create("/api/games/" + game.id())).body(game);
        } catch (DataIntegrityViolationException e) {
            // Two requests with the same key raced; the unique index let one through. Return that one.
            if (key != null) {
                var winner = gameService.findByIdempotencyKey(user.id(), key);
                if (winner.isPresent()) {
                    return ResponseEntity.ok(winner.get());
                }
            }
            throw e;
        }
    }

    private static UUID parseKey(String header) {
        if (header == null) {
            return null;
        }
        try {
            if (header.length() != 36) {
                throw new IllegalArgumentException();
            }
            return UUID.fromString(header);
        } catch (IllegalArgumentException e) {
            throw new ChessException(ErrorCode.VALIDATION_FAILED, "Idempotency-Key må være en UUID.");
        }
    }

    @GetMapping("/api/games/{id}")
    GameView get(@PathVariable long id) {
        return gameService.get(id);
    }

    @GetMapping("/api/games/pending")
    List<GameView> pendingForMe(@AuthenticationPrincipal CurrentUser user) {
        return gameService.pendingFor(user.id());
    }

    @GetMapping("/api/admin/games/pending")
    List<GameView> allPending() {
        return gameService.allPending();
    }

    @PostMapping("/api/games/{id}/confirm")
    GameView confirm(@PathVariable long id, @AuthenticationPrincipal CurrentUser user) {
        return gameService.confirm(user, id);
    }

    @PostMapping("/api/games/{id}/reject")
    GameView reject(@PathVariable long id, @Valid @RequestBody(required = false) RejectRequest body,
                    @AuthenticationPrincipal CurrentUser user) {
        return gameService.reject(user, id, body == null ? null : body.reason());
    }

    @PostMapping("/api/games/live")
    ResponseEntity<GameView> startLive(@Valid @RequestBody LiveRequest body, @AuthenticationPrincipal CurrentUser user) {
        GameView game = gameService.startLive(user, new GameService.LiveCommand(body.whiteId(), body.blackId(), body.rated(),
                body.category(), body.timeControl()));
        return ResponseEntity.created(URI.create("/api/games/" + game.id())).body(game);
    }

    @PostMapping("/api/games/{id}/moves")
    GameService.MoveResponse move(@PathVariable long id, @Valid @RequestBody MoveRequest body,
                                  @AuthenticationPrincipal CurrentUser user) {
        return gameService.move(user, id, body.move(), body.expectedPly());
    }

    @PostMapping("/api/games/{id}/finish")
    GameView finish(@PathVariable long id, @Valid @RequestBody FinishRequest body, @AuthenticationPrincipal CurrentUser user) {
        return gameService.finish(user, id, body.result(), body.termination());
    }

    record ReportRequest(@NotNull GameResult result, @Size(max = 200_000) String pgn, Termination termination) {
    }

    record FeatureRequest(@NotNull Boolean featured) {
    }

    /** Result of a scheduled (tournament) game. */
    @PostMapping("/api/games/{id}/report")
    GameView report(@PathVariable long id, @Valid @RequestBody ReportRequest body, @AuthenticationPrincipal CurrentUser user) {
        guards.limit(guards.limits().gameSubmissionPolicy(), "user:" + user.id());
        return gameService.report(user, id, body.result(), body.pgn(), body.termination());
    }

    @PostMapping("/api/games/{id}/start")
    GameView startScheduled(@PathVariable long id, @AuthenticationPrincipal CurrentUser user) {
        return gameService.startScheduled(user, id);
    }

    @PostMapping("/api/admin/games/{id}/feature")
    GameView feature(@PathVariable long id, @Valid @RequestBody FeatureRequest body, @AuthenticationPrincipal CurrentUser user) {
        return gameService.feature(user, id, body.featured());
    }

    @PostMapping("/api/games/{id}/abort")
    GameView abort(@PathVariable long id, @AuthenticationPrincipal CurrentUser user) {
        return gameService.abort(user, id);
    }

    @PutMapping("/api/games/{id}/analysis")
    AnalysisView saveAnalysis(@PathVariable long id, @RequestBody AnalysisPayload body, @AuthenticationPrincipal CurrentUser user) {
        return analysisService.save(user, id, body);
    }

    @GetMapping("/api/games/{id}/analysis")
    AnalysisView analysis(@PathVariable long id) {
        return analysisService.get(id);
    }
}
