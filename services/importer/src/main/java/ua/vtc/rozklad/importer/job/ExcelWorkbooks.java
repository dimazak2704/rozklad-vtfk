package ua.vtc.rozklad.importer.job;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.UnsupportedFileFormatException;
import org.apache.poi.ooxml.POIXMLException;
import org.apache.poi.openxml4j.exceptions.OpenXML4JRuntimeException;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.util.RecordFormatException;

/**
 * Opens an uploaded Excel file. POI reads every sheet while opening, so a damaged package (a cut
 * sheet XML, a broken zip entry) fails here — with POI's runtime exceptions as well as
 * {@link IOException} — and becomes an {@link ImportParseException} with a plain message; errors
 * in our own parsing code later are not mistaken for a damaged file.
 */
public final class ExcelWorkbooks {

    public static final String PROTECTED = "Файл Excel захищено паролем — збережіть його без пароля.";

    private ExcelWorkbooks() {
    }

    /**
     * @param damagedMessage the message for a file that cannot be read
     * @throws ImportParseException when the file is password-protected or damaged
     */
    public static Workbook open(byte[] content, String damagedMessage) {
        try {
            return WorkbookFactory.create(new ByteArrayInputStream(content));
        } catch (EncryptedDocumentException e) {
            throw new ImportParseException(PROTECTED, e);
        } catch (IOException | POIXMLException | OpenXML4JRuntimeException | UnsupportedFileFormatException
                 | RecordFormatException e) {
            throw new ImportParseException(damagedMessage, e);
        }
    }
}
