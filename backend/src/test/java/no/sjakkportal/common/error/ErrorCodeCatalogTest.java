package no.sjakkportal.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ErrorCodeCatalogTest {

    @Test
    void codesAreUnique() {
        List<String> codes = Arrays.stream(ErrorCode.values()).map(ErrorCode::code).toList();
        assertThat(codes).doesNotHaveDuplicates();
    }

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    void codeFollowsTheCatalogFormat(ErrorCode errorCode) {
        assertThat(errorCode.code()).matches("CHESS-[A-Z]{3,8}-\\d{3}");
    }

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    void everyCodeHasAnErrorStatusAndMessages(ErrorCode errorCode) {
        assertThat(errorCode.status().isError()).isTrue();
        assertThat(errorCode.title()).isNotBlank();
        assertThat(errorCode.defaultDetail()).isNotBlank().endsWith(".");
    }

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    void userFacingMessagesNeverMentionImplementationDetails(ErrorCode errorCode) {
        assertThat(errorCode.defaultDetail().toLowerCase())
                .doesNotContain("exception", "sql", "stack", "null", "java");
    }

    @Test
    void serverErrorsAreLimitedToTheCatchAllAndUpstreamFailures() {
        assertThat(Arrays.stream(ErrorCode.values()).filter(e -> e.status().is5xxServerError()))
                .containsExactlyInAnyOrder(ErrorCode.INTERNAL_ERROR, ErrorCode.IMPORT_UPSTREAM_FAILED);
    }
}
