package ua.vtc.rozklad.importer.job;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

/**
 * Detects the real format of an upload by its content: PDF/JPEG/PNG/GIF/WEBP signatures, OOXML
 * packages by their entries (only names are read, nothing is inflated), OLE2 Word files by the
 * «WordDocument» stream. Legacy XLS and anything else are not supported.
 */
public final class FileTypeSniffer {

    private static final byte[] PDF = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final byte[] GIF87 = "GIF87a".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] GIF89 = "GIF89a".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] RIFF = "RIFF".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] WEBP = "WEBP".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ZIP = {'P', 'K', 3, 4};
    private static final byte[] OLE2 = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
    private static final int MAX_ZIP_ENTRIES = 2_000;

    private FileTypeSniffer() {
    }

    public static Optional<FileType> detect(byte[] content) {
        if (content == null || content.length < 8) {
            return Optional.empty();
        }
        if (startsWith(content, PDF)) {
            return Optional.of(FileType.PDF);
        }
        if (startsWith(content, JPEG)) {
            return Optional.of(FileType.JPEG);
        }
        if (startsWith(content, PNG)) {
            return Optional.of(FileType.PNG);
        }
        if (startsWith(content, GIF87) || startsWith(content, GIF89)) {
            return Optional.of(FileType.GIF);
        }
        if (content.length >= 12 && startsWith(content, RIFF) && Arrays.equals(content, 8, 12, WEBP, 0, 4)) {
            return Optional.of(FileType.WEBP);
        }
        if (startsWith(content, ZIP)) {
            return detectOoxml(content);
        }
        if (startsWith(content, OLE2)) {
            return detectOle2(content);
        }
        return Optional.empty();
    }

    private static Optional<FileType> detectOoxml(byte[] content) {
        boolean contentTypes = false;
        boolean word = false;
        boolean excel = false;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            int entries = 0;
            while ((entry = zip.getNextEntry()) != null && entries++ < MAX_ZIP_ENTRIES) {
                String name = entry.getName();
                contentTypes |= name.equals("[Content_Types].xml");
                word |= name.equals("word/document.xml");
                excel |= name.equals("xl/workbook.xml");
            }
        } catch (IOException | IllegalArgumentException e) {
            // обрізаний пакет: тип за тим, що вже прочитано, — розбір потім скаже «файл пошкоджено»
            if (!contentTypes || word == excel) {
                return Optional.empty();
            }
        }
        if (!contentTypes) {
            return Optional.empty();
        }
        if (word) {
            return Optional.of(FileType.DOCX);
        }
        return excel ? Optional.of(FileType.XLSX) : Optional.empty();
    }

    private static Optional<FileType> detectOle2(byte[] content) {
        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(content))) {
            return fs.getRoot().hasEntry("WordDocument") ? Optional.of(FileType.DOC) : Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static boolean startsWith(byte[] content, byte[] prefix) {
        return content.length >= prefix.length && Arrays.equals(content, 0, prefix.length, prefix, 0, prefix.length);
    }
}
