package ua.vtc.rozklad.importer.job;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

/**
 * Detects the real format of an upload by its content: PDF/JPEG/PNG/GIF/WEBP signatures, OOXML
 * packages by their entry names, OLE2 Word files by the «WordDocument» stream. Legacy XLS and anything
 * else are not supported.
 * <p>
 * Entry names come from the zip's central directory, so nothing is inflated: reading the package entry by
 * entry inflates every entry it passes, and a 20 MB zip bomb kept the upload request (and its database
 * connection) busy for a minute. Only a cut package without a central directory is read entry by entry, and
 * then at most {@value #MAX_INFLATED_BYTES} bytes are inflated.
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
    static final int MAX_INFLATED_BYTES = 32 * 1024 * 1024;
    private static final int END_OF_CENTRAL_DIRECTORY = 0x06054b50;
    private static final int CENTRAL_DIRECTORY_ENTRY = 0x02014b50;
    private static final int END_RECORD_SIZE = 22;
    private static final int MAX_COMMENT = 0xFFFF;

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
        PackageNames names = new PackageNames();
        if (!readCentralDirectory(content, names)) {
            readLocalEntries(content, names);
        }
        return names.type();
    }

    /** Names from the central directory, without inflating anything; {@code false} when there is none. */
    static boolean readCentralDirectory(byte[] content, PackageNames names) {
        ByteBuffer zip = ByteBuffer.wrap(content).order(ByteOrder.LITTLE_ENDIAN);
        int end = -1;
        for (int at = content.length - END_RECORD_SIZE; at >= Math.max(0, content.length - END_RECORD_SIZE - MAX_COMMENT);
             at--) {
            if (zip.getInt(at) == END_OF_CENTRAL_DIRECTORY) {
                end = at;
                break;
            }
        }
        if (end < 0) {
            return false;
        }
        long offset = Integer.toUnsignedLong(zip.getInt(end + 16));
        int entries = Short.toUnsignedInt(zip.getShort(end + 10));
        if (offset >= content.length) {
            return false; // ZIP64 (0xFFFFFFFF) or a damaged record: read the entries one by one instead
        }
        int at = (int) offset;
        for (int i = 0; i < Math.min(entries, MAX_ZIP_ENTRIES); i++) {
            if (at + 46 > content.length || zip.getInt(at) != CENTRAL_DIRECTORY_ENTRY) {
                break;
            }
            int nameLength = Short.toUnsignedInt(zip.getShort(at + 28));
            int extraLength = Short.toUnsignedInt(zip.getShort(at + 30));
            int commentLength = Short.toUnsignedInt(zip.getShort(at + 32));
            if (at + 46 + nameLength > content.length) {
                break;
            }
            names.add(new String(content, at + 46, nameLength, StandardCharsets.ISO_8859_1));
            at += 46 + nameLength + extraLength + commentLength;
        }
        return true;
    }

    /** A cut package (no central directory): entries one by one, inflating at most {@link #MAX_INFLATED_BYTES}. */
    private static void readLocalEntries(byte[] content, PackageNames names) {
        byte[] buffer = new byte[8192];
        long inflated = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            int entries = 0;
            while ((entry = zip.getNextEntry()) != null && entries++ < MAX_ZIP_ENTRIES) {
                names.add(entry.getName());
                if (names.decided()) {
                    return;
                }
                // дочитуємо запис самі, щоб рахувати розпаковане (getNextEntry розпакував би його без меж)
                int read;
                while ((read = zip.read(buffer)) > 0) {
                    inflated += read;
                    if (inflated > MAX_INFLATED_BYTES) {
                        return;
                    }
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            // обрізаний пакет: тип за тим, що вже прочитано, — розбір потім скаже «файл пошкоджено»
        }
    }

    /** Which OOXML package the entry names describe. */
    static final class PackageNames {

        private boolean contentTypes;
        private boolean word;
        private boolean excel;

        void add(String name) {
            contentTypes |= name.equals("[Content_Types].xml");
            word |= name.equals("word/document.xml");
            excel |= name.equals("xl/workbook.xml");
        }

        /** A Word part decides at once (it wins over a workbook part, as before). */
        boolean decided() {
            return contentTypes && word;
        }

        Optional<FileType> type() {
            if (!contentTypes) {
                return Optional.empty();
            }
            if (word) {
                return Optional.of(FileType.DOCX);
            }
            return excel ? Optional.of(FileType.XLSX) : Optional.empty();
        }
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
