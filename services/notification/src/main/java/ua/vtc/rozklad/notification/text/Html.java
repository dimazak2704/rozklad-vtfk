package ua.vtc.rozklad.notification.text;

import java.util.ArrayList;
import java.util.List;

/**
 * Telegram HTML ({@code parse_mode=HTML}): every dynamic value goes through {@link #escape}.
 * Message builders keep tags balanced within one line, so {@link #split} may cut between lines.
 */
public final class Html {

    /** Telegram allows 4096 characters after entity parsing; markup is not counted, this is a safe bound. */
    public static final int MAX_MESSAGE = 4000;
    public static final int MAX_CAPTION = 1000;

    private Html() {
    }

    public static String escape(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    public static String b(String text) {
        return "<b>" + escape(text) + "</b>";
    }

    public static String i(String text) {
        return "<i>" + escape(text) + "</i>";
    }

    public static String s(String text) {
        return "<s>" + escape(text) + "</s>";
    }

    public static String code(String text) {
        return "<code>" + escape(text) + "</code>";
    }

    public static String link(String url, String text) {
        return "<a href=\"" + escape(url) + "\">" + escape(text) + "</a>";
    }

    /**
     * Splits a message into parts of at most {@code max} characters, preferring blank lines, then
     * line breaks. A single line longer than {@code max} is cut at spaces (such lines carry no
     * markup in this service).
     */
    public static List<String> split(String html, int max) {
        if (max < 2) {
            throw new IllegalArgumentException("max too small: " + max);
        }
        if (html.length() <= max) {
            return List.of(html);
        }
        Packer packer = new Packer(max);
        for (String block : html.split("\n\n", -1)) {
            if (block.length() <= max) {
                packer.add(block, "\n\n");
                continue;
            }
            String separator = "\n\n";
            for (String line : block.split("\n", -1)) {
                if (line.length() <= max) {
                    packer.add(line, separator);
                } else {
                    packer.flush();
                    cutAtSpaces(line, max).forEach(packer::addWhole);
                }
                separator = "\n";
            }
        }
        packer.flush();
        return packer.parts;
    }

    private static List<String> cutAtSpaces(String line, int max) {
        List<String> chunks = new ArrayList<>();
        String rest = line;
        while (rest.length() > max) {
            int cut = rest.lastIndexOf(' ', max);
            if (cut <= 0) {
                cut = max;
            }
            chunks.add(rest.substring(0, cut).strip());
            rest = rest.substring(cut).strip();
        }
        if (!rest.isEmpty()) {
            chunks.add(rest);
        }
        return chunks;
    }

    /** Greedy packing of text units into parts of at most {@code max} characters. */
    private static final class Packer {

        private final int max;
        private final List<String> parts = new ArrayList<>();
        private final StringBuilder current = new StringBuilder();

        Packer(int max) {
            this.max = max;
        }

        void add(String unit, String separator) {
            if (!current.isEmpty() && current.length() + separator.length() + unit.length() > max) {
                flush();
            }
            if (!current.isEmpty()) {
                current.append(separator);
            }
            current.append(unit);
        }

        void addWhole(String part) {
            flush();
            if (!part.isBlank()) {
                parts.add(part);
            }
        }

        void flush() {
            String part = current.toString().strip();
            if (!part.isEmpty()) {
                parts.add(part);
            }
            current.setLength(0);
        }
    }
}
