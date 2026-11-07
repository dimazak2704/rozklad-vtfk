package ua.vtc.rozklad.notification.documents;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.notification.config.DocumentProperties;
import ua.vtc.rozklad.notification.config.OfficeProperties;

/**
 * {@code soffice --headless --convert-to pdf} ({@code SOFFICE_PATH}). One conversion at a time:
 * LibreOffice cannot share its user profile between processes, and it is memory hungry.
 */
@Component
public class LibreOfficeConverter implements DocxToPdfConverter {

    private static final Logger log = LoggerFactory.getLogger(LibreOfficeConverter.class);
    private static final int LOG_TAIL = 400;

    private final OfficeProperties office;
    private final DocumentProperties documents;
    private final Semaphore oneAtATime = new Semaphore(1, true);

    public LibreOfficeConverter(OfficeProperties office, DocumentProperties documents) {
        this.office = office;
        this.documents = documents;
    }

    @Override
    public boolean isAvailable() {
        return office.conversionEnabled();
    }

    @Override
    public byte[] toPdf(byte[] docx) {
        if (!isAvailable()) {
            throw new ConverterUnavailableException("SOFFICE_PATH не задано");
        }
        long timeoutMs = documents.conversionTimeout().toMillis();
        try {
            if (!oneAtATime.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) {
                throw new DocumentRenderingException("LibreOffice is busy for longer than " + timeoutMs + " ms");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DocumentRenderingException("Interrupted while waiting for LibreOffice", e);
        }
        Path work = null;
        try {
            Files.createDirectories(documents.cachePath());
            work = Files.createTempDirectory(documents.cachePath(), "convert-");
            return convert(docx, work, timeoutMs);
        } catch (IOException e) {
            throw new DocumentRenderingException("Cannot prepare the conversion: " + e.getMessage(), e);
        } finally {
            oneAtATime.release();
            deleteQuietly(work);
        }
    }

    private byte[] convert(byte[] docx, Path work, long timeoutMs) throws IOException {
        Path input = work.resolve("order.docx");
        Path output = work.resolve("order.pdf");
        Path logFile = work.resolve("soffice.log");
        Files.write(input, docx);
        Path profile = documents.cachePath().resolve("libreoffice-profile");
        List<String> command = List.of(office.sofficePath(),
                "-env:UserInstallation=" + profile.toUri(),
                "--headless", "--invisible", "--norestore", "--nolockcheck", "--nodefault", "--nofirststartwizard",
                "--convert-to", "pdf", "--outdir", work.toString(), input.toString());
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(logFile.toFile()).start();
        } catch (IOException e) {
            throw new ConverterUnavailableException("не вдалося запустити " + office.sofficePath());
        }
        try {
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new DocumentRenderingException("LibreOffice did not finish in " + timeoutMs + " ms");
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new DocumentRenderingException("Interrupted while converting", e);
        }
        if (process.exitValue() != 0 || !Files.exists(output)) {
            throw new DocumentRenderingException("LibreOffice exit code " + process.exitValue() + ": "
                    + tail(logFile));
        }
        byte[] pdf = Files.readAllBytes(output);
        log.debug("Converted DOCX ({} bytes) to PDF ({} bytes)", docx.length, pdf.length);
        return pdf;
    }

    private static String tail(Path logFile) {
        try {
            String text = Files.readString(logFile, StandardCharsets.UTF_8).strip();
            return text.length() <= LOG_TAIL ? text : text.substring(text.length() - LOG_TAIL);
        } catch (IOException | UncheckedIOException e) {
            return "(no log)";
        }
    }

    private static void deleteQuietly(Path directory) {
        if (directory == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.debug("Cannot delete {}: {}", path, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.debug("Cannot clean {}: {}", directory, e.getMessage());
        }
    }
}
