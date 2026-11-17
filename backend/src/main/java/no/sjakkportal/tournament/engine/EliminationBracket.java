package no.sjakkportal.tournament.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import no.sjakkportal.common.error.ChessException;
import no.sjakkportal.common.error.ErrorCode;

/**
 * Single- and double-elimination brackets with standard seeding (1 v N, 2 v N-1, ... arranged so the
 * top two seeds can only meet in the final) and byes for the top seeds when the field is not a power of two.
 *
 * <p>Chess games can be drawn; a bracket match needs a winner, so the caller reports the player who
 * advances (after a replay or armageddon). In double elimination the grand final is reset once if the
 * losers-bracket finalist wins it, so that every eliminated player has lost exactly twice.
 *
 * <p>The bracket is deterministic: replaying the same reports on the same seeds rebuilds the same state,
 * which is how it is restored from the database.
 *
 * <p>Internally the bracket is a graph of matches. Each match forwards its winner (and, in the winners
 * bracket of a double elimination, its loser) to a slot of a later match. An empty seed position
 * forwards "nobody", which makes byes fall out of the same mechanism without special cases.
 */
public final class EliminationBracket {

    private static final class Slot {
        final Node node;
        final boolean first;

        Slot(Node node, boolean first) {
            this.node = node;
            this.first = first;
        }
    }

    private static final class Node {
        final int id;
        final BracketSide side;
        final int round;
        Long playerA;
        Long playerB;
        boolean aFilled;
        boolean bFilled;
        Long winner;
        /** True once the outcome is known, including "nobody advances" for a match that never had players. */
        boolean settled;
        Slot winnerTo;
        Slot loserTo;

        Node(int id, BracketSide side, int round) {
            this.id = id;
            this.side = side;
            this.round = round;
        }

        BracketMatch view() {
            return new BracketMatch(id, side, round, playerA, playerB, winner);
        }
    }

    private final List<Node> nodes = new ArrayList<>();
    private final Map<Long, Integer> losses = new HashMap<>();
    private final boolean doubleElimination;
    private Node decider;
    private Long champion;

    private EliminationBracket(boolean doubleElimination) {
        this.doubleElimination = doubleElimination;
    }

    public static EliminationBracket single(List<Long> seededPlayers) {
        return build(seededPlayers, false);
    }

    public static EliminationBracket doubleElimination(List<Long> seededPlayers) {
        return build(seededPlayers, true);
    }

    private static EliminationBracket build(List<Long> seededPlayers, boolean doubleElimination) {
        if (new HashSet<>(seededPlayers).size() != seededPlayers.size()) {
            throw new IllegalArgumentException("Duplicate players in bracket");
        }
        if (seededPlayers.size() < 2) {
            throw new ChessException(ErrorCode.TOURNAMENT_TOO_FEW_PLAYERS);
        }
        EliminationBracket bracket = new EliminationBracket(doubleElimination);
        bracket.construct(seededPlayers);
        return bracket;
    }

    private Node node(BracketSide side, int round) {
        Node node = new Node(nodes.size() + 1, side, round);
        nodes.add(node);
        return node;
    }

    private void construct(List<Long> seeds) {
        int size = 2;
        while (size < seeds.size()) {
            size *= 2;
        }
        // Winners bracket: round r has size / 2^r matches.
        List<List<Node>> winners = new ArrayList<>();
        for (int count = size / 2, round = 1; count >= 1; count /= 2, round++) {
            List<Node> matches = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                matches.add(node(BracketSide.WINNERS, round));
            }
            if (!winners.isEmpty()) {
                List<Node> previous = winners.get(winners.size() - 1);
                for (int i = 0; i < previous.size(); i++) {
                    previous.get(i).winnerTo = new Slot(matches.get(i / 2), i % 2 == 0);
                }
            }
            winners.add(matches);
        }
        Node winnersFinal = winners.get(winners.size() - 1).get(0);

        if (!doubleElimination) {
            decider = winnersFinal;
        } else {
            Node grandFinal = buildLosersBracket(winners);
            winnersFinal.winnerTo = new Slot(grandFinal, true);
            decider = grandFinal;
        }

        // Seat the players. Seeds beyond the field are empty and resolve as byes.
        int[] order = seedOrder(size);
        List<Node> firstRound = winners.get(0);
        for (int i = 0; i < firstRound.size(); i++) {
            deliver(new Slot(firstRound.get(i), true), seedOrEmpty(seeds, order[2 * i]));
            deliver(new Slot(firstRound.get(i), false), seedOrEmpty(seeds, order[2 * i + 1]));
        }
    }

    /**
     * Losers bracket. Losers of winners round 1 pair off; then for every later winners round the
     * survivors first meet that round's losers and, except after the last, pair off among themselves.
     *
     * @return the grand final, whose second slot is fed by the losers-bracket champion
     */
    private Node buildLosersBracket(List<List<Node>> winners) {
        Node grandFinal = new Node(-1, BracketSide.GRAND_FINAL, 1);
        List<Node> survivors;
        int round = 1;

        List<Node> firstRound = winners.get(0);
        if (firstRound.size() == 1) {
            // Two-player bracket: the loser of the only match goes straight to the grand final.
            firstRound.get(0).loserTo = new Slot(grandFinal, false);
            return register(grandFinal);
        }
        survivors = new ArrayList<>();
        for (int i = 0; i < firstRound.size(); i += 2) {
            Node match = node(BracketSide.LOSERS, round);
            firstRound.get(i).loserTo = new Slot(match, true);
            firstRound.get(i + 1).loserTo = new Slot(match, false);
            survivors.add(match);
        }
        for (int w = 1; w < winners.size(); w++) {
            List<Node> dropping = winners.get(w);
            round++;
            List<Node> merged = new ArrayList<>();
            for (int i = 0; i < survivors.size(); i++) {
                Node match = node(BracketSide.LOSERS, round);
                survivors.get(i).winnerTo = new Slot(match, true);
                // Reversed order keeps players who just met in the winners bracket apart for longer.
                dropping.get(dropping.size() - 1 - i).loserTo = new Slot(match, false);
                merged.add(match);
            }
            survivors = merged;
            if (survivors.size() > 1) {
                round++;
                List<Node> halved = new ArrayList<>();
                for (int i = 0; i < survivors.size(); i += 2) {
                    Node match = node(BracketSide.LOSERS, round);
                    survivors.get(i).winnerTo = new Slot(match, true);
                    survivors.get(i + 1).winnerTo = new Slot(match, false);
                    halved.add(match);
                }
                survivors = halved;
            }
        }
        survivors.get(0).winnerTo = new Slot(grandFinal, false);
        return register(grandFinal);
    }

    /** Gives a node built ahead of time its final id, so ids follow bracket order. */
    private Node register(Node template) {
        Node node = node(template.side, template.round);
        for (Node other : nodes) {
            if (other.winnerTo != null && other.winnerTo.node == template) {
                other.winnerTo = new Slot(node, other.winnerTo.first);
            }
            if (other.loserTo != null && other.loserTo.node == template) {
                other.loserTo = new Slot(node, other.loserTo.first);
            }
        }
        return node;
    }

    /** Standard bracket order for a power-of-two size: [1, 8, 4, 5, 2, 7, 3, 6] for eight. */
    private static int[] seedOrder(int size) {
        int[] order = {1};
        while (order.length < size) {
            int[] next = new int[order.length * 2];
            for (int i = 0; i < order.length; i++) {
                next[2 * i] = order[i];
                next[2 * i + 1] = order.length * 2 + 1 - order[i];
            }
            order = next;
        }
        return order;
    }

    private static Long seedOrEmpty(List<Long> seeds, int seed) {
        return seed <= seeds.size() ? seeds.get(seed - 1) : null;
    }

    /** Puts a player (or nobody) into a slot, and settles the match if that leaves it without an opponent. */
    private void deliver(Slot slot, Long player) {
        if (slot == null) {
            return;
        }
        Node node = slot.node;
        if (slot.first) {
            node.playerA = player;
            node.aFilled = true;
        } else {
            node.playerB = player;
            node.bFilled = true;
        }
        if (node.aFilled && node.bFilled && !node.settled && (node.playerA == null || node.playerB == null)) {
            // A bye: the lone player advances without a game and without a loss.
            node.winner = node.playerA != null ? node.playerA : node.playerB;
            settle(node, null);
        }
    }

    private void settle(Node node, Long loser) {
        node.settled = true;
        deliver(node.winnerTo, node.winner);
        deliver(node.loserTo, loser);
    }

    /** Every match of the bracket, byes and not-yet-determined matches included, ordered by id. */
    public List<BracketMatch> matches() {
        return nodes.stream().map(Node::view).toList();
    }

    /** Matches with both players known and no winner yet. */
    public List<BracketMatch> readyMatches() {
        return nodes.stream().map(Node::view).filter(BracketMatch::isReady).toList();
    }

    /**
     * Records the winner of a ready match and advances the bracket.
     *
     * @throws ChessException CHESS-TOURN-006 if the match does not exist,
     *         is not ready, is already decided, or {@code winner} is not one of its players
     */
    public void report(int matchId, long winner) {
        if (matchId < 1 || matchId > nodes.size()) {
            throw new ChessException(ErrorCode.TOURNAMENT_PAIRING_CONFLICT);
        }
        Node node = nodes.get(matchId - 1);
        if (node.settled || node.playerA == null || node.playerB == null
                || (node.playerA != winner && node.playerB != winner)) {
            throw new ChessException(ErrorCode.TOURNAMENT_PAIRING_CONFLICT);
        }
        Long loser = node.playerA == winner ? node.playerB : node.playerA;
        node.winner = winner;
        losses.merge(loser, 1, Integer::sum);
        settle(node, loser);

        if (node == decider) {
            boolean resetNeeded = doubleElimination && node.round == 1 && losses(winner) == 1 && losses(loser) == 1;
            if (resetNeeded) {
                // The losers-bracket finalist won: both now have one loss, so they play once more.
                Node reset = node(BracketSide.GRAND_FINAL, 2);
                reset.playerA = node.playerA;
                reset.playerB = node.playerB;
                reset.aFilled = true;
                reset.bFilled = true;
                decider = reset;
            } else {
                champion = winner;
            }
        }
    }

    public boolean isFinished() {
        return champion != null;
    }

    public Optional<Long> champion() {
        return Optional.ofNullable(champion);
    }

    /** Number of bracket matches a player has lost so far. */
    public int losses(long player) {
        return losses.getOrDefault(player, 0);
    }
}
