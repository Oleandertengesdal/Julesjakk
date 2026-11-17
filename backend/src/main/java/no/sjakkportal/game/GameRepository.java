package no.sjakkportal.game;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GameRepository extends JpaRepository<Game, Long> {

    /**
     * Loads the game holding a row lock until the transaction ends. Every state change (move, finish,
     * confirm, reject, abort) starts here, so concurrent requests for one game run one after another
     * and each sees the state the previous one left.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from Game g where g.id = :id")
    Optional<Game> lockById(@Param("id") long id);

    Optional<Game> findByCreatedByAndClientRequestId(long createdBy, UUID clientRequestId);
}
