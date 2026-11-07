package ua.vtc.rozklad.importer.program;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import ua.vtc.rozklad.importer.program.ProgramDraft.Hours;
import ua.vtc.rozklad.importer.report.Issues;
import ua.vtc.rozklad.importer.report.RowType;
import ua.vtc.rozklad.importer.text.TextNormalizer;

/**
 * Cross-check with the thematic plan (a table of topics with Лекц. | Практ. | Лаб. | Сем.
 * columns): the per-topic hours should add up to the structure table. Real programs disagree
 * (labs 18 by topics vs 20 in the structure), which becomes a warning.
 */
final class TopicPlan {

    private static final Pattern NUMBER = Pattern.compile("^\\d{1,3}$");
    private static final int MIN_TOPIC_ROWS = 3;

    private enum Kind {
        LECTURES("лекц", "лекції"), PRACTICE("практ", "практичні"), LABS("лаб", "лабораторні"), SEMINARS("сем", "семінарські");

        private final String fragment;
        private final String label;

        Kind(String fragment, String label) {
            this.fragment = fragment;
            this.label = label;
        }
    }

    private TopicPlan() {
    }

    static void check(ProgramDocument document, Hours hours, Issues issues) {
        for (ProgramDocument.Table table : document.tables()) {
            Map<Kind, Integer> columns = headerColumns(table.rows());
            if (columns == null || topicRows(table.rows()) < MIN_TOPIC_ROWS) {
                continue;
            }
            Map<Kind, Integer> sums = new EnumMap<>(Kind.class);
            for (List<String> row : table.rows()) {
                String text = TextNormalizer.key(String.join(" ", row));
                if (text.contains("разом") || text.contains("всього") || text.contains("усього")) {
                    continue;
                }
                columns.forEach((kind, column) -> {
                    if (column < row.size() && NUMBER.matcher(row.get(column).strip()).matches()) {
                        sums.merge(kind, Integer.parseInt(row.get(column).strip()), Integer::sum);
                    }
                });
            }
            compare(Kind.LECTURES, sums, hours.lectures(), table, issues);
            compare(Kind.PRACTICE, sums, hours.practice(), table, issues);
            compare(Kind.LABS, sums, hours.labs(), table, issues);
            compare(Kind.SEMINARS, sums, hours.seminars(), table, issues);
            return;
        }
    }

    private static Map<Kind, Integer> headerColumns(List<List<String>> rows) {
        for (int r = 0; r < Math.min(4, rows.size()); r++) {
            List<String> row = rows.get(r);
            Map<Kind, Integer> columns = new EnumMap<>(Kind.class);
            for (int c = 0; c < row.size(); c++) {
                String label = TextNormalizer.key(row.get(c));
                for (Kind kind : Kind.values()) {
                    if (label.startsWith(kind.fragment) && !columns.containsKey(kind)) {
                        columns.put(kind, c);
                    }
                }
            }
            if (columns.containsKey(Kind.LECTURES) && columns.size() >= 2) {
                return columns;
            }
        }
        return null;
    }

    private static int topicRows(List<List<String>> rows) {
        int count = 0;
        for (List<String> row : rows) {
            String text = TextNormalizer.key(String.join(" ", row));
            if (text.contains("тема") || text.contains("лабораторна робота") || text.contains("практичне заняття")) {
                count++;
            }
        }
        return count;
    }

    private static void compare(Kind kind, Map<Kind, Integer> sums, Integer structure, ProgramDocument.Table table,
                                Issues issues) {
        int byTopics = sums.getOrDefault(kind, 0);
        int declared = structure == null ? 0 : structure;
        if (byTopics == 0 && declared == 0 || byTopics == declared) {
            return;
        }
        issues.warn(RowType.HOURS, kind.label + ": " + byTopics + " / " + declared, "таблиця " + (table.index() + 1),
                "За тематичним планом " + kind.label + " — " + byTopics + " год, а в таблиці структури — " + declared
                        + " год.");
    }
}
