package com.yann.autodownload.util;

import it.tdlight.jni.TdApi;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Converts a limited HTML subset to a TDLib {@link TdApi.FormattedText} with entities,
 * without calling any TDLib async API (avoids deadlock on the TDLib thread).
 *
 * <p>Supported tags: {@code <b>}, {@code <strong>}, {@code <i>}, {@code <em>},
 * {@code <code>}, {@code <pre>}.  HTML entities {@code &amp;}, {@code &lt;},
 * {@code &gt;} are unescaped.
 */
public final class HtmlUtil {

    private HtmlUtil() {}

    /** Escapes a plain-text value for safe embedding inside an HTML template. */
    public static String esc(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;");
    }

    /**
     * Parses a simple HTML string and returns a {@link TdApi.FormattedText}
     * with the corresponding text entities.
     */
    public static TdApi.FormattedText parse(String html) {
        if (html == null || html.isEmpty()) {
            return new TdApi.FormattedText("", new TdApi.TextEntity[0]);
        }

        StringBuilder sb = new StringBuilder();
        List<TdApi.TextEntity> entities = new ArrayList<>();
        // Stack entries: [typeOrdinal, startOffsetInSb]
        Deque<int[]> stack = new ArrayDeque<>();

        int i = 0;
        while (i < html.length()) {
            char c = html.charAt(i);

            if (c == '<') {
                int end = html.indexOf('>', i);
                if (end == -1) { sb.append(c); i++; continue; }

                String tag = html.substring(i + 1, end).trim();
                i = end + 1;

                if (tag.startsWith("/")) {
                    // Closing tag: pop and create entity
                    if (!stack.isEmpty()) {
                        int[] entry = stack.pop();
                        int offset = entry[1];
                        int length = sb.length() - offset;
                        if (length > 0) {
                            TdApi.TextEntityType type = ordinalToType(entry[0]);
                            if (type != null) {
                                TdApi.TextEntity entity = new TdApi.TextEntity();
                                entity.offset = offset;
                                entity.length = length;
                                entity.type   = type;
                                entities.add(entity);
                            }
                        }
                    }
                } else {
                    // Opening tag: push ordinal + current text offset
                    stack.push(new int[]{tagToOrdinal(tag.toLowerCase()), sb.length()});
                }

            } else if (c == '&') {
                // Unescape HTML entities
                if (html.startsWith("&amp;", i))  { sb.append('&'); i += 5; }
                else if (html.startsWith("&lt;",  i)) { sb.append('<'); i += 4; }
                else if (html.startsWith("&gt;",  i)) { sb.append('>'); i += 4; }
                else { sb.append(c); i++; }

            } else {
                sb.append(c);
                i++;
            }
        }

        return new TdApi.FormattedText(sb.toString(),
                entities.toArray(new TdApi.TextEntity[0]));
    }

    private static int tagToOrdinal(String tag) {
        return switch (tag) {
            case "b", "strong" -> 0;
            case "i", "em"     -> 1;
            case "code"        -> 2;
            case "pre"         -> 3;
            default            -> -1;
        };
    }

    private static TdApi.TextEntityType ordinalToType(int ordinal) {
        return switch (ordinal) {
            case 0 -> new TdApi.TextEntityTypeBold();
            case 1 -> new TdApi.TextEntityTypeItalic();
            case 2 -> new TdApi.TextEntityTypeCode();
            case 3 -> new TdApi.TextEntityTypePre();
            default -> null;
        };
    }
}
