package no.sjakkportal.importer;

/** @param source {@code LICHESS} or {@code CHESS_COM}; @param id the site's game id, already validated */
public record ExternalGameRef(String source, String id) {
}
