package no.sjakkportal.common;

/**
 * Thrown by contract stubs during the red phase of TDD. No production code path may
 * reference this once Phase 2 is complete; a test enforces that.
 */
public class NotImplementedYet extends UnsupportedOperationException {

    public NotImplementedYet() {
        super("Not implemented yet (TDD red phase)");
    }
}
