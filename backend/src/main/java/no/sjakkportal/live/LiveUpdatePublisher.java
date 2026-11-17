package no.sjakkportal.live;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import no.sjakkportal.game.GameChangedEvent;
import no.sjakkportal.game.GameService;
import no.sjakkportal.tournament.TournamentChangedEvent;
import no.sjakkportal.tournament.TournamentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Pushes changes to subscribers once the transaction that made them has committed, so a change that
 * rolls back is never shown. Broadcasting happens on one background thread: the request returns
 * without waiting for it, its database connection is already released, and updates for a game are
 * sent in the order they happened.
 */
@Component
public class LiveUpdatePublisher {

    private static final Logger log = LoggerFactory.getLogger(LiveUpdatePublisher.class);

    private final SimpMessagingTemplate messaging;
    private final GameService gameService;
    private final TournamentService tournamentService;
    private final TvService tvService;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "live-updates");
        thread.setDaemon(true);
        return thread;
    });

    public LiveUpdatePublisher(SimpMessagingTemplate messaging, GameService gameService, TournamentService tournamentService,
                               TvService tvService) {
        this.messaging = messaging;
        this.gameService = gameService;
        this.tournamentService = tournamentService;
        this.tvService = tvService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void gameChanged(GameChangedEvent event) {
        executor.execute(() -> safely(() -> {
            messaging.convertAndSend("/topic/games/" + event.gameId(), Map.of("type", "game", "game", gameService.get(event.gameId())));
            messaging.convertAndSend("/topic/tv", tvService.current());
        }));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void tournamentChanged(TournamentChangedEvent event) {
        executor.execute(() -> safely(() -> messaging.convertAndSend("/topic/tournaments/" + event.tournamentId(),
                Map.of("type", "tournament", "tournament", tournamentService.detail(event.tournamentId())))));
    }

    /** Waits until every update queued so far has been sent. Used by tests and on shutdown. */
    public void awaitIdle() {
        try {
            executor.submit(() -> { }).get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @jakarta.annotation.PreDestroy
    void shutdown() {
        awaitIdle();
        executor.shutdown();
    }

    private static void safely(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            // A failed broadcast must never affect anything else; clients resynchronise by reloading.
            log.warn("Live update could not be sent", e);
        }
    }
}
