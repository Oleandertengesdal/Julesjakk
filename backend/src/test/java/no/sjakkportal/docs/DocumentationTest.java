package no.sjakkportal.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import no.sjakkportal.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

/** Keeps the documentation in step with the code: codes and settings cannot drift apart silently. */
class DocumentationTest {

    private static final Path ROOT = Path.of("..");

    private static String read(String path) throws IOException {
        return Files.readString(ROOT.resolve(path));
    }

    private static Set<String> matches(String text, String regex) {
        Set<String> found = new TreeSet<>();
        Matcher m = Pattern.compile(regex).matcher(text);
        while (m.find()) {
            found.add(m.group(1));
        }
        return found;
    }

    @Test
    void everyServerErrorCodeIsDocumentedWithItsStatus() throws IOException {
        String doc = read("docs/API_AND_ERRORS.md");
        for (ErrorCode code : ErrorCode.values()) {
            assertThat(doc).as(code.code()).containsPattern("\\| `" + code.code() + "` \\| " + code.status().value() + " \\|");
        }
    }

    @Test
    void everyClientErrorCodeIsDocumented() throws IOException {
        String doc = read("docs/API_AND_ERRORS.md");
        Set<String> clientCodes = matches(read("frontend/src/lib/api.ts"), "'(CHESS-CLIENT-\\d{3})'");
        assertThat(clientCodes).isNotEmpty();
        clientCodes.forEach(code -> assertThat(doc).as(code).contains("`" + code + "`"));
    }

    @Test
    void theDocsMentionNoCodeThatDoesNotExist() throws IOException {
        Set<String> known = Arrays.stream(ErrorCode.values()).map(ErrorCode::code).collect(Collectors.toCollection(TreeSet::new));
        known.addAll(matches(read("frontend/src/lib/api.ts"), "'(CHESS-CLIENT-\\d{3})'"));
        for (String file : new String[] {"README.md", "docs/API_AND_ERRORS.md", "docs/ARCHITECTURE.md", "docs/SECURITY.md", "docs/DEPLOYMENT.md"}) {
            assertThat(known).as(file).containsAll(matches(read(file), "(CHESS-[A-Z]+-\\d{3})"));
        }
    }

    @Test
    void everyEnvironmentVariableIsInTheReadmeTable() throws IOException {
        String readme = read("README.md");
        Set<String> variables = matches(read("backend/src/main/resources/application.yml"), "\\$\\{([A-Z_]+)[:}]");
        variables.addAll(matches(read("frontend/.env.example"), "(?m)^(VITE_[A-Z_]+)="));
        assertThat(variables).contains("DATABASE_URL", "SJAKKPORTAL_JWT_SECRET", "VITE_WS_URL");
        variables.forEach(variable -> assertThat(readme).as(variable).containsPattern("\\| `" + variable + "` \\|"));
    }

    @Test
    void theBlueprintOnlySetsVariablesTheBackendReads() throws IOException {
        Set<String> read = matches(read("backend/src/main/resources/application.yml"), "\\$\\{([A-Z_]+)[:}]");
        assertThat(read).containsAll(matches(read("render.yaml"), "- key: ([A-Z_]+)"));
    }
}
