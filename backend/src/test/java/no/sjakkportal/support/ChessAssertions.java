package no.sjakkportal.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import no.sjakkportal.common.error.ChessException;
import no.sjakkportal.common.error.ErrorCode;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

public final class ChessAssertions {

    private ChessAssertions() {
    }

    /** Asserts that the call fails with a {@link ChessException} carrying exactly this code. */
    public static void assertFailsWith(ErrorCode expected, ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown)
                .as("expected ChessException with %s", expected.code())
                .isInstanceOf(ChessException.class);
        assertThat(((ChessException) thrown).errorCode()).isEqualTo(expected);
    }

    /**
     * Asserts that hostile input is handled: the call either succeeds or fails with a cataloged
     * {@link ChessException}. Anything else (NPE, StackOverflowError, IndexOutOfBounds...) is a bug.
     */
    public static void assertHandledGracefully(String description, ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        if (thrown != null) {
            assertThat(thrown).as("input: %s", description).isInstanceOf(ChessException.class);
        }
    }
}
