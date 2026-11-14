package ua.vtc.rozklad.importer.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

/** The type of an OOXML upload comes from entry names, without inflating what the package holds. */
class FileTypeSnifferTest {

    private static final byte[] CONTENT_TYPES = "<Types/>".getBytes(StandardCharsets.UTF_8);
    private static final byte[] WORKBOOK = "<workbook/>".getBytes(StandardCharsets.UTF_8);

    /**
     * Names are read from the central directory: an entry before them that would have to be inflated (here its
     * deflate data is broken, so inflating it fails) is not touched.
     */
    @Test
    void namesComeFromTheCentralDirectoryWithoutInflatingEntries() throws IOException {
        byte[] zip = zip(new Entry("xl/media/image1.bin", new byte[4096]), new Entry("[Content_Types].xml", CONTENT_TYPES),
                new Entry("xl/workbook.xml", WORKBOOK));
        int data = dataOffset(zip, "xl/media/image1.bin");
        zip[data] = (byte) 0xFF; // BTYPE 11: «invalid block type» for anyone who inflates the entry

        assertThat(FileTypeSniffer.detect(zip)).contains(FileType.XLSX);
    }

    /** A cut package (no central directory) is read entry by entry, but inflating stops at the budget. */
    @Test
    void aCutPackageIsInflatedOnlyUpToTheBudget() throws IOException {
        byte[] bomb = new byte[FileTypeSniffer.MAX_INFLATED_BYTES + 1024 * 1024];
        byte[] zip = zip(new Entry("xl/media/zeros.bin", bomb), new Entry("[Content_Types].xml", CONTENT_TYPES),
                new Entry("xl/workbook.xml", WORKBOOK));
        byte[] cut = Arrays.copyOf(zip, centralDirectoryOffset(zip));

        assertThat(FileTypeSniffer.detect(cut)).isEmpty();

        byte[] small = zip(new Entry("[Content_Types].xml", CONTENT_TYPES), new Entry("xl/workbook.xml", WORKBOOK),
                new Entry("xl/worksheets/sheet1.xml", new byte[1024]));
        assertThat(FileTypeSniffer.detect(Arrays.copyOf(small, centralDirectoryOffset(small)))).contains(FileType.XLSX);
    }

    @Test
    void wordWinsAndAPackageWithoutContentTypesIsNotOoxml() throws IOException {
        assertThat(FileTypeSniffer.detect(zip(new Entry("[Content_Types].xml", CONTENT_TYPES),
                new Entry("word/document.xml", WORKBOOK)))).contains(FileType.DOCX);
        assertThat(FileTypeSniffer.detect(zip(new Entry("xl/workbook.xml", WORKBOOK)))).isEmpty();
        assertThat(FileTypeSniffer.detect(zip(new Entry("[Content_Types].xml", CONTENT_TYPES)))).isEmpty();
    }

    private record Entry(String name, byte[] data) {
    }

    private static byte[] zip(Entry... entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Entry entry : entries) {
                zip.putNextEntry(new ZipEntry(entry.name()));
                zip.write(entry.data());
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    /** Start of the compressed data of the entry's local header. */
    private static int dataOffset(byte[] zip, String name) {
        byte[] wanted = name.getBytes(StandardCharsets.US_ASCII);
        for (int at = 0; at + 30 < zip.length; at++) {
            if (zip[at] == 'P' && zip[at + 1] == 'K' && zip[at + 2] == 3 && zip[at + 3] == 4) {
                int nameLength = (zip[at + 26] & 0xFF) | (zip[at + 27] & 0xFF) << 8;
                int extraLength = (zip[at + 28] & 0xFF) | (zip[at + 29] & 0xFF) << 8;
                if (Arrays.equals(zip, at + 30, at + 30 + nameLength, wanted, 0, wanted.length)) {
                    return at + 30 + nameLength + extraLength;
                }
            }
        }
        throw new IllegalArgumentException(name);
    }

    /** Offset of the central directory, read from the end-of-central-directory record. */
    private static int centralDirectoryOffset(byte[] zip) {
        for (int at = zip.length - 22; at >= 0; at--) {
            if (zip[at] == 'P' && zip[at + 1] == 'K' && zip[at + 2] == 5 && zip[at + 3] == 6) {
                return (zip[at + 16] & 0xFF) | (zip[at + 17] & 0xFF) << 8 | (zip[at + 18] & 0xFF) << 16
                        | (zip[at + 19] & 0xFF) << 24;
            }
        }
        throw new IllegalArgumentException("no central directory");
    }
}
