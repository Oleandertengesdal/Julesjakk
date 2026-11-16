package no.sjakkportal.common.error;

/** The only exception type domain and service code throws for expected failures. */
public class ChessException extends RuntimeException {

    private final ErrorCode errorCode;

    public ChessException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultDetail());
    }

    public ChessException(ErrorCode errorCode, String detail) {
        super(detail);
        this.errorCode = errorCode;
    }

    public ChessException(ErrorCode errorCode, String detail, Throwable cause) {
        super(detail, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
