package ua.vtc.rozklad.importer.program;

import java.util.List;

/**
 * Text content of a program document: paragraphs in reading order and tables as rows of cell
 * texts. DOCX cells are expanded by their grid span so columns line up across header rows; PDF has
 * no tables (text only).
 */
public record ProgramDocument(List<String> paragraphs, List<Table> tables) {

    public ProgramDocument {
        paragraphs = List.copyOf(paragraphs);
        tables = List.copyOf(tables);
    }

    public record Table(int index, List<List<String>> rows) {

        public Table {
            rows = rows.stream().map(List::copyOf).toList();
        }
    }

    /** All text (paragraphs and table cells) for the AI fallback. */
    public String fullText() {
        StringBuilder text = new StringBuilder();
        paragraphs.forEach(paragraph -> text.append(paragraph).append('\n'));
        for (Table table : tables) {
            text.append('\n');
            for (List<String> row : table.rows()) {
                text.append(String.join(" | ", row)).append('\n');
            }
        }
        return text.toString();
    }
}
