package no.sjakkportal.rating;

import static no.sjakkportal.support.ChessAssertions.assertFailsWith;
import static org.assertj.core.api.Assertions.assertThat;

import no.sjakkportal.common.error.ErrorCode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class TimeCategoryTest {

    @ParameterizedTest
    @CsvSource({
            "1+0,BLITZ", "3+0,BLITZ", "3+2,BLITZ", "5+0,BLITZ", "5+3,BLITZ", "9+0,BLITZ", "5+4,BLITZ",
            "5+5,RAPID", "10+0,RAPID", "10,RAPID", "15+10,RAPID", "25+10,RAPID", "30+0,RAPID", "59+0,RAPID", "45+14,RAPID",
            "60+0,CLASSICAL", "45+15,CLASSICAL", "90+30,CLASSICAL", "120,CLASSICAL"})
    void classifiesByEstimatedGameLength(String timeControl, TimeCategory expected) {
        assertThat(TimeCategory.fromTimeControl(timeControl)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    void gamesWithoutAClockAreClassical(String timeControl) {
        assertThat(TimeCategory.fromTimeControl(timeControl)).isEqualTo(TimeCategory.CLASSICAL);
    }

    @Test
    void toleratesSurroundingWhitespace() {
        assertThat(TimeCategory.fromTimeControl(" 5 + 3 ")).isEqualTo(TimeCategory.BLITZ);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0+0", "0", "-5+3", "5+-3", "5+", "+3", "fem+tre", "5+3+2", "5:00", "1e3+0", "99999999999+0",
            "5+99999999999", "601+0", "5+181", "<script>", "5.5+0", "٥+0"})
    void rejectsNonsense(String timeControl) {
        assertFailsWith(ErrorCode.GAME_INVALID_TIME_CONTROL, () -> TimeCategory.fromTimeControl(timeControl));
    }

    @Test
    void resolvesFromWhicheverIsGiven() {
        assertThat(TimeCategory.resolve(null, null)).isEqualTo(TimeCategory.CLASSICAL);
        assertThat(TimeCategory.resolve(TimeCategory.BLITZ, null)).isEqualTo(TimeCategory.BLITZ);
        assertThat(TimeCategory.resolve(null, "15+10")).isEqualTo(TimeCategory.RAPID);
        assertThat(TimeCategory.resolve(TimeCategory.RAPID, "15+10")).isEqualTo(TimeCategory.RAPID);
    }

    /** Otherwise a 3-minute game could be booked against someone's rapid rating. */
    @Test
    void categoryThatContradictsTheClockIsRejected() {
        assertFailsWith(ErrorCode.GAME_INVALID_TIME_CONTROL, () -> TimeCategory.resolve(TimeCategory.RAPID, "3+0"));
        assertFailsWith(ErrorCode.GAME_INVALID_TIME_CONTROL, () -> TimeCategory.resolve(TimeCategory.BLITZ, "90+30"));
    }
}
