package ua.vtc.rozklad.importer.program;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.importer.program.ProgramDraft.Hours;
import ua.vtc.rozklad.importer.report.Issues;
import ua.vtc.rozklad.importer.report.RowType;
import ua.vtc.rozklad.importer.text.TextNormalizer;

/**
 * Deterministic part of DISCIPLINE_PROGRAM (DOMAIN §11): finds the table «Структура навчальної
 * дисципліни загальна й за семестрами» by its header labels (Всього | На заняттях | СРС | Лекції |
 * Практ. | Лабор. | Семінар. | Іспит | Залік | Конс.), reads its data row and validates the sums,
 * the hours mentioned in the text and the per-topic plan. The data is dirty, so every mismatch is
 * a warning; the semester printed in a program is never trusted.
 */
@Component
public class ProgramStructureExtractor {

    private static final Pattern LEADING_NUMBER = Pattern.compile("^\\s*(\\d{1,4})");
    private static final Pattern SEMESTER = Pattern.compile("семестр\\D{0,3}(\\d{1,2})");
    private static final Pattern TEXT_TOTAL = Pattern.compile("відведено\\s+(\\d{2,4})\\s+год");
    private static final Pattern TABLE_TOTAL = Pattern.compile("загальна кількість годин\\s*[-–—:]?\\s*(\\d{2,4})");
    private static final Pattern WEEKLY = Pattern.compile("аудиторних\\s*[-–—:]\\s*(\\d+(?:[.,]\\d+)?)");
    private static final Pattern CREDITS = Pattern.compile("кількість кредитів\\s*[-–—:]?\\s*(\\d+(?:[.,]\\d+)?)");
    private static final Pattern CYCLE_COMMISSION = Pattern.compile("^циклова комісія\\s+(.+)$");
    private static final Pattern NUMERIC_TOKEN = Pattern.compile("^\\d{1,4}$");
    private static final Pattern WORDS = Pattern.compile("[\\p{L}'’ʼ]+|[^\\p{L}'’ʼ]+");
    /** «робоча програма навчальної дисципліни «Кібербезпека»» — the name in the title line (group 1). */
    private static final Pattern TITLE_WITH_NAME =
            Pattern.compile("(?iu)^(?:робоча\\s+)?програма\\s+(?:навчальної\\s+)?дисципліни\\s*[:\\-–—]?\\s*(\\S.*)$");
    private static final List<String> NOT_A_NAME = List.of("спеціальн", "освітня програма", "освітньо", "галузь", "галузі", "для студент",
            "для здобувач", "рівень", "ступінь", "циклова комісія", "кафедр", "курс ", "семестр", "форма навчання",
            "затверджено", "погоджено", "розробник", "укладач", "міністерство", "вінницький", "коледж");

    /** Column kinds of the structure table, matched by label fragments. */
    enum Column {
        TOTAL("всього", "усього"), CLASSROOM("заняттях", "аудитор"), SELF_STUDY("срс", "самост"),
        LECTURES("лекц"), PRACTICE("практ"), LABS("лаб"), SEMINARS("семін"), EXAM("іспит", "екзамен"),
        CREDIT("залік"), CONSULTATIONS("конс");

        private final String[] fragments;

        Column(String... fragments) {
            this.fragments = fragments;
        }

        boolean matches(String label) {
            for (String fragment : fragments) {
                if (label.contains(fragment)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Result of the deterministic pass; {@code hours} is {@code null} when the table was not found. */
    public record Extraction(String disciplineName, String disciplineRaw, String cycleCommission, Hours hours,
                             Integer semester, Double hoursPerWeek, Double credits, ProgramDraft.Source source,
                             String location) {
    }

    public Extraction extract(ProgramDocument document, Issues issues) {
        String raw = disciplineTitle(document.paragraphs());
        String name = raw == null ? null : sentenceCase(raw);
        if (name == null) {
            issues.warn(RowType.HOURS, null, "титульна сторінка",
                    "Не знайдено назви дисципліни на титульній сторінці — зіставте дисципліну вручну.");
        }
        String allText = TextNormalizer.key(document.fullText());
        Double hoursPerWeek = firstDecimal(WEEKLY, allText);
        Double credits = firstDecimal(CREDITS, allText);
        String commission = document.paragraphs().stream()
                .map(paragraph -> CYCLE_COMMISSION.matcher(TextNormalizer.key(paragraph)))
                .filter(Matcher::matches).map(matcher -> matcher.group(1)).findFirst().orElse(null);

        Optional<TableHit> hit = findStructureTable(document);
        Hours hours = null;
        Integer semester = null;
        ProgramDraft.Source source = null;
        String location = null;
        if (hit.isPresent()) {
            hours = hit.get().hours();
            semester = hit.get().semester();
            source = ProgramDraft.Source.TABLE;
            location = "таблиця " + (hit.get().tableIndex() + 1);
        } else if (document.tables().isEmpty()) {
            Optional<Hours> fromText = fromPdfText(document.paragraphs(), issues);
            if (fromText.isPresent()) {
                hours = fromText.get();
                source = ProgramDraft.Source.PDF_TEXT;
                location = "текст PDF";
            }
        }
        if (hours != null) {
            validate(hours, allText, document, issues, location);
            if (semester != null) {
                issues.info(RowType.HOURS, "семестр " + semester, location, "У програмі вказано семестр " + semester
                        + " — він буває застарілим, тож групу й семестр обирає адміністратор під час застосування.");
            }
        }
        return new Extraction(name, raw, commission, hours, semester, hoursPerWeek, credits, source,
                location);
    }

    // ───────────────────────── таблиця структури ─────────────────────────

    private record TableHit(int tableIndex, Hours hours, Integer semester) {
    }

    private Optional<TableHit> findStructureTable(ProgramDocument document) {
        for (ProgramDocument.Table table : document.tables()) {
            int dataRow = firstNumericRow(table.rows());
            if (dataRow <= 0) {
                continue;
            }
            List<String> labels = columnLabels(table.rows(), dataRow);
            Map<Column, Integer> columns = mapColumns(labels);
            if (!columns.containsKey(Column.TOTAL) || !columns.containsKey(Column.LECTURES)) {
                continue;
            }
            List<String> data = table.rows().get(dataRow);
            Hours hours = new Hours(number(data, columns.get(Column.TOTAL)), number(data, columns.get(Column.CLASSROOM)),
                    number(data, columns.get(Column.SELF_STUDY)), number(data, columns.get(Column.LECTURES)),
                    number(data, columns.get(Column.PRACTICE)), number(data, columns.get(Column.LABS)),
                    number(data, columns.get(Column.SEMINARS)), number(data, columns.get(Column.CONSULTATIONS)),
                    mark(data, columns.get(Column.EXAM)), mark(data, columns.get(Column.CREDIT)));
            return Optional.of(new TableHit(table.index(), hours, semester(table.rows(), dataRow)));
        }
        return Optional.empty();
    }

    private static int firstNumericRow(List<List<String>> rows) {
        for (int i = 0; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            if (!row.isEmpty() && NUMERIC_TOKEN.matcher(row.getFirst()).matches()) {
                return i;
            }
        }
        return -1;
    }

    /** For every column, the lowest non-empty header text above the data row. */
    private static List<String> columnLabels(List<List<String>> rows, int dataRow) {
        int width = rows.get(dataRow).size();
        List<String> labels = new ArrayList<>();
        for (int column = 0; column < width; column++) {
            String label = "";
            for (int row = dataRow - 1; row >= 0 && label.isEmpty(); row--) {
                List<String> header = rows.get(row);
                if (column < header.size()) {
                    label = TextNormalizer.key(header.get(column));
                }
            }
            labels.add(label);
        }
        return labels;
    }

    private static Map<Column, Integer> mapColumns(List<String> labels) {
        Map<Column, Integer> columns = new HashMap<>();
        for (int i = 0; i < labels.size(); i++) {
            for (Column column : Column.values()) {
                if (column.matches(labels.get(i)) && !columns.containsKey(column)) {
                    columns.put(column, i);
                    break;
                }
            }
        }
        return columns;
    }

    private static Integer semester(List<List<String>> rows, int dataRow) {
        for (int row = 0; row < dataRow; row++) {
            for (String cell : rows.get(row)) {
                Matcher matcher = SEMESTER.matcher(TextNormalizer.key(cell));
                if (matcher.find()) {
                    return Integer.parseInt(matcher.group(1));
                }
            }
        }
        return null;
    }

    private static Integer number(List<String> row, Integer column) {
        if (column == null || column >= row.size()) {
            return null;
        }
        Matcher matcher = LEADING_NUMBER.matcher(row.get(column));
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }

    /** «+», «х», «так» or a number in the exam/credit column means yes; «-» or empty means no. */
    private static boolean mark(List<String> row, Integer column) {
        if (column == null || column >= row.size()) {
            return false;
        }
        String value = TextNormalizer.key(row.get(column));
        return !value.isEmpty() && !value.equals("-") && !value.equals("–") && !value.equals("—") && !value.equals("ні");
    }

    // ───────────────────────── PDF без таблиць ─────────────────────────

    /**
     * PDF text loses empty cells, so the data line can be mapped only when it is unambiguous: total,
     * classroom and self-study hours that add up, followed by exactly four class-type numbers.
     */
    private static Optional<Hours> fromPdfText(List<String> lines, Issues issues) {
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (TextNormalizer.key(lines.get(i)).contains("структура навчальної дисципліни")) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            return Optional.empty();
        }
        for (int i = start + 1; i < Math.min(lines.size(), start + 20); i++) {
            String[] tokens = TextNormalizer.key(lines.get(i)).split(" ");
            if (tokens.length < 4 || !NUMERIC_TOKEN.matcher(tokens[0]).matches()) {
                continue;
            }
            List<Integer> numbers = new ArrayList<>();
            boolean marks = false;
            for (String token : tokens) {
                if (NUMERIC_TOKEN.matcher(token).matches()) {
                    numbers.add(Integer.parseInt(token));
                } else if (token.equals("+") || token.equals("х")) {
                    marks = true;
                }
            }
            if (numbers.size() == 7 && numbers.get(0) == numbers.get(1) + numbers.get(2)
                    && numbers.get(1) == numbers.get(3) + numbers.get(4) + numbers.get(5) + numbers.get(6)) {
                return Optional.of(new Hours(numbers.get(0), numbers.get(1), numbers.get(2), numbers.get(3), numbers.get(4),
                        numbers.get(5), numbers.get(6), null, false, marks));
            }
            issues.warn(RowType.HOURS, lines.get(i), "текст PDF", "Рядок годин у PDF неоднозначний (порожні клітинки "
                    + "в тексті не видно) — години треба визначити інакше.");
            return Optional.empty();
        }
        return Optional.empty();
    }

    // ───────────────────────── перевірки ─────────────────────────

    private static void validate(Hours hours, String allText, ProgramDocument document, Issues issues, String location) {
        if (hours.total() != null && hours.classroom() != null && hours.selfStudy() != null
                && hours.total() != hours.classroom() + hours.selfStudy()) {
            issues.warn(RowType.HOURS, hours.total() + " ≠ " + hours.classroom() + " + " + hours.selfStudy(), location,
                    "Усього годин (" + hours.total() + ") не дорівнює сумі годин на заняттях (" + hours.classroom()
                            + ") і СРС (" + hours.selfStudy() + ").");
        }
        if (hours.classroom() != null && hours.classroomSum() > 0 && hours.classroom() != hours.classroomSum()) {
            issues.warn(RowType.HOURS, String.valueOf(hours.classroom()), location, "Годин на заняттях " + hours.classroom()
                    + ", а лекцій, практичних, лабораторних і семінарів разом " + hours.classroomSum() + ".");
        }
        if (!hours.exam() && !hours.credit()) {
            issues.warn(RowType.HOURS, null, location, "У таблиці не позначено ні іспиту, ні заліку.");
        }
        Matcher textTotal = TEXT_TOTAL.matcher(allText);
        if (textTotal.find() && hours.total() != null && Integer.parseInt(textTotal.group(1)) != hours.total()) {
            issues.warn(RowType.HOURS, textTotal.group(), location, "У тексті програми сказано «" + textTotal.group()
                    + "», а в таблиці структури — " + hours.total() + " год. Перевірте, яке значення правильне.");
        }
        Matcher tableTotal = TABLE_TOTAL.matcher(allText);
        if (tableTotal.find() && hours.total() != null && Integer.parseInt(tableTotal.group(1)) != hours.total()) {
            issues.warn(RowType.HOURS, tableTotal.group(), location, "В описі дисципліни «" + tableTotal.group()
                    + "», а в таблиці структури — " + hours.total() + " год.");
        }
        TopicPlan.check(document, hours, issues);
    }

    // ───────────────────────── назва дисципліни ─────────────────────────

    /**
     * The discipline name of the title page: in the title line itself («Робоча програма навчальної
     * дисципліни «Кібербезпека»») or in one of the next lines. Lines that describe the program
     * rather than name it («Спеціальність 123 …», «Освітня програма …», «для студентів …») are never
     * taken; {@code null} when no line qualifies, so the admin chooses the discipline.
     */
    static String disciplineTitle(List<String> paragraphs) {
        for (int i = 0; i < paragraphs.size(); i++) {
            String key = TextNormalizer.key(paragraphs.get(i));
            String line = TextNormalizer.unifyApostrophes(TextNormalizer.clean(paragraphs.get(i)));
            Matcher inline = TITLE_WITH_NAME.matcher(line);
            if (inline.matches()) {
                String candidate = stripQuotes(inline.group(1));
                if (isName(candidate)) {
                    return candidate;
                }
            }
            if (key.equals("програма") || key.equals("робоча програма") || key.startsWith("робоча програма навчальної")
                    || key.startsWith("програма навчальної")) {
                for (int j = i + 1; j < Math.min(paragraphs.size(), i + 5); j++) {
                    String candidate = stripQuotes(paragraphs.get(j));
                    String candidateKey = TextNormalizer.key(candidate);
                    if (candidateKey.isEmpty() || candidateKey.startsWith("навчальної дисципліни")
                            || candidateKey.equals("дисципліни")) {
                        continue;
                    }
                    return isName(candidate) ? candidate : null;
                }
            }
        }
        return null;
    }

    /** Not a description line of the title page and not a bare number. */
    private static boolean isName(String candidate) {
        String key = TextNormalizer.key(candidate);
        return !key.isEmpty() && key.chars().anyMatch(Character::isLetter)
                && NOT_A_NAME.stream().noneMatch(key::startsWith);
    }

    private static String stripQuotes(String text) {
        return TextNormalizer.clean(text.replaceAll("[«»\"“”]", ""));
    }

    private static final java.util.Set<String> FUNCTION_WORDS = java.util.Set.of(
            "та", "і", "й", "в", "у", "з", "із", "зі", "на", "до", "за", "по", "від", "для", "як", "а", "о");

    /**
     * «ЕКОНОМІКА ТА УПРАВЛІННЯ ІТ-ПРОЄКТАМИ» → «Економіка та управління ІТ-проєктами»: words of up to three
     * letters stay capital (acronyms such as «ІТ», «ПК»), except conjunctions and prepositions.
     */
    static String sentenceCase(String text) {
        if (!text.equals(text.toUpperCase(TextNormalizer.UK))) {
            return text;
        }
        StringBuilder result = new StringBuilder();
        Matcher word = WORDS.matcher(text);
        boolean first = true;
        while (word.find()) {
            String part = word.group();
            if (!Character.isLetter(part.charAt(0))) {
                result.append(part);
                continue;
            }
            String lower = part.toLowerCase(TextNormalizer.UK);
            boolean acronym = part.length() <= 3 && !FUNCTION_WORDS.contains(lower);
            if (acronym) {
                result.append(part);
            } else if (first) {
                result.append(lower.substring(0, 1).toUpperCase(TextNormalizer.UK)).append(lower.substring(1));
            } else {
                result.append(lower);
            }
            first = false;
        }
        return result.toString();
    }

    private static Double firstDecimal(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? Double.parseDouble(matcher.group(1).replace(',', '.')) : null;
    }
}
