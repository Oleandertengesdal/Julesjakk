package no.sjakkportal.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Fails until every contract stub from the red phase has a real implementation. */
class RedPhaseGuardTest {

    @Test
    void noProductionCodeStillThrowsNotImplementedYet() throws IOException {
        Path sources = Path.of("src/main/java");
        try (Stream<Path> files = Files.walk(sources)) {
            List<String> stubs = files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.endsWith("NotImplementedYet.java"))
                    .filter(path -> {
                        try {
                            return Files.readString(path).contains("NotImplementedYet");
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .map(path -> sources.relativize(path).toString()).sorted().toList();
            assertThat(stubs).as("classes that are still stubs").isEmpty();
        }
    }
}
