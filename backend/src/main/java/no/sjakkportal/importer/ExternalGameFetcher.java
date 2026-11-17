package no.sjakkportal.importer;

/** Downloads the PGN of one game from an external site. */
public interface ExternalGameFetcher {

    /** @throws no.sjakkportal.common.error.ChessException CHESS-IMPORT-002 when the site cannot deliver the game */
    String fetchPgn(ExternalGameRef ref);
}
