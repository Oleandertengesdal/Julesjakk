package no.sjakkportal.chess;

import java.util.Optional;
import no.sjakkportal.rating.GameResult;

/** Result of applying one move during live entry. */
public record MoveOutcome(String san,
                          String uci,
                          String fenAfter,
                          boolean check,
                          Optional<GameResult> boardOutcome,
                          Optional<Termination> termination) {
}
