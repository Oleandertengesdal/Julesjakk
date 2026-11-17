package no.sjakkportal.common.text;

import java.util.regex.Pattern;

/**
 * Input sanitisation for free-text fields (names, comments, reasons). The frontend escapes on output
 * as well; removing markup on the way in means stored data is safe for every future consumer too.
 */
public final class Sanitizer {

    private static final Pattern MARKUP = Pattern.compile("<[^>]*>");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\n\\t]]");

    private Sanitizer() {
    }

    /** Strips tags, stray angle brackets and control characters, trims, and caps the length. */
    public static String plainText(String input, int maxLength) {
        if (input == null) {
            return null;
        }
        String text = MARKUP.matcher(input).replaceAll("");
        text = CONTROL.matcher(text).replaceAll("").replace("<", "").replace(">", "").strip();
        return text.length() > maxLength ? text.substring(0, maxLength).strip() : text;
    }
}
