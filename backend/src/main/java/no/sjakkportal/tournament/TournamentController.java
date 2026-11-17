package no.sjakkportal.tournament;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import no.sjakkportal.chess.Termination;
import no.sjakkportal.common.error.ChessException;
import no.sjakkportal.common.error.ErrorCode;
import no.sjakkportal.game.GameView;
import no.sjakkportal.rating.GameResult;
import no.sjakkportal.rating.TimeCategory;
import no.sjakkportal.security.CurrentUser;
import no.sjakkportal.security.RequestGuards;
import no.sjakkportal.tournament.TournamentViews.Detail;
import no.sjakkportal.tournament.TournamentViews.Summary;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tournaments")
public class TournamentController {

    record CreateRequest(@NotBlank @Size(max = 100) String name, @Size(max = 1000) String description,
                         @NotNull TournamentFormat format, TimeCategory category, @Size(max = 32) String timeControl,
                         Boolean rated, DrawResolution drawResolution, Integer rounds, Instant startsAt, Instant endsAt) {
    }

    record ParticipantRequest(@NotNull Long userId) {
    }

    record ArenaGameRequest(@NotNull Long whiteId, @NotNull Long blackId, @NotNull GameResult result,
                            @Size(max = 200_000) String pgn, Termination termination) {
    }

    private final TournamentService tournaments;
    private final RequestGuards guards;

    public TournamentController(TournamentService tournaments, RequestGuards guards) {
        this.tournaments = tournaments;
        this.guards = guards;
    }

    @GetMapping
    List<Summary> list() {
        return tournaments.list();
    }

    @GetMapping("/{id}")
    Detail detail(@PathVariable long id) {
        return tournaments.detail(id);
    }

    @PostMapping
    ResponseEntity<Detail> create(@Valid @RequestBody CreateRequest body, @AuthenticationPrincipal CurrentUser user) {
        requireAdmin(user);
        Detail created = tournaments.create(user, new TournamentService.CreateCommand(body.name(), body.description(), body.format(),
                body.category(), body.timeControl(), body.rated(), body.drawResolution(), body.rounds(), body.startsAt(), body.endsAt()));
        return ResponseEntity.created(URI.create("/api/tournaments/" + created.id())).body(created);
    }

    @PostMapping("/{id}/participants")
    ResponseEntity<Detail> addParticipant(@PathVariable long id, @Valid @RequestBody ParticipantRequest body,
                                          @AuthenticationPrincipal CurrentUser user) {
        requireAdmin(user);
        return ResponseEntity.status(201).body(tournaments.addParticipant(id, body.userId()));
    }

    @DeleteMapping("/{id}/participants/{userId}")
    Detail removeParticipant(@PathVariable long id, @PathVariable long userId, @AuthenticationPrincipal CurrentUser user) {
        requireAdmin(user);
        return tournaments.removeParticipant(id, userId);
    }

    @PostMapping("/{id}/join")
    Detail join(@PathVariable long id, @AuthenticationPrincipal CurrentUser user) {
        return tournaments.addParticipant(id, user.id());
    }

    @PostMapping("/{id}/leave")
    Detail leave(@PathVariable long id, @AuthenticationPrincipal CurrentUser user) {
        return tournaments.removeParticipant(id, user.id());
    }

    @PostMapping("/{id}/start")
    Detail start(@PathVariable long id, @AuthenticationPrincipal CurrentUser user) {
        requireAdmin(user);
        return tournaments.start(user, id);
    }

    @PostMapping("/{id}/cancel")
    Detail cancel(@PathVariable long id, @AuthenticationPrincipal CurrentUser user) {
        requireAdmin(user);
        return tournaments.cancel(user, id);
    }

    @PostMapping("/{id}/finish")
    Detail finish(@PathVariable long id, @AuthenticationPrincipal CurrentUser user) {
        requireAdmin(user);
        return tournaments.finishArena(user, id);
    }

    @PostMapping("/{id}/arena-games")
    ResponseEntity<GameView> arenaGame(@PathVariable long id, @Valid @RequestBody ArenaGameRequest body,
                                       @AuthenticationPrincipal CurrentUser user) {
        guards.limit(guards.limits().gameSubmissionPolicy(), "user:" + user.id());
        GameView game = tournaments.arenaGame(user, id, new TournamentService.ArenaGameCommand(body.whiteId(), body.blackId(),
                body.result(), body.pgn(), body.termination()));
        return ResponseEntity.created(URI.create("/api/games/" + game.id())).body(game);
    }

    private static void requireAdmin(CurrentUser user) {
        if (!user.isAdmin()) {
            throw new ChessException(ErrorCode.AUTH_ACCESS_DENIED);
        }
    }
}
