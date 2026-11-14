package ua.vtc.rozklad.importer.program;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Timeout.ThreadMode;
import ua.vtc.rozklad.importer.job.FileType;

/** A horizontally merged cell is repeated per grid column; a crafted span must not repeat it billions of times. */
class ProgramDocumentReaderSpanTest {

    @Test
    @Timeout(value = 30, threadMode = ThreadMode.SEPARATE_THREAD)
    void aCraftedGridSpanIsCutToWordsColumnLimit() throws IOException {
        byte[] docx;
        try (XWPFDocument document = new XWPFDocument()) {
            XWPFTable table = document.createTable(2, 2);
            table.getRow(0).getCell(0).setText("Тема");
            table.getRow(0).getCell(1).setText("Години");
            XWPFTableCell crafted = table.getRow(1).getCell(0);
            crafted.setText("Вступ");
            crafted.getCTTc().addNewTcPr().addNewGridSpan().setVal(BigInteger.valueOf(2_000_000_000));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.write(out);
            docx = out.toByteArray();
        }

        ProgramDocument read = new ProgramDocumentReader().read(FileType.DOCX, docx);

        assertThat(read.tables().getFirst().rows().get(0)).containsExactly("Тема", "Години");
        assertThat(read.tables().getFirst().rows().get(1)).hasSize(ProgramDocumentReader.MAX_GRID_SPAN + 1)
                .startsWith("Вступ", "Вступ");
    }

    @Test
    void ordinarySpansStayAsTheyAre() {
        assertThat(ProgramDocumentReader.clampSpan(0)).isEqualTo(1);
        assertThat(ProgramDocumentReader.clampSpan(3)).isEqualTo(3);
        assertThat(ProgramDocumentReader.clampSpan(63)).isEqualTo(63);
        assertThat(ProgramDocumentReader.clampSpan(Integer.MAX_VALUE)).isEqualTo(63);
    }
}
