package ua.vtc.rozklad.core.timetable.internal.importing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 of an import request, stored with the draft it created: a repeat with the same
 * {@code Idempotency-Key} must carry the same request (record {@code toString} lists every field
 * of the command and its lessons in order).
 */
final class ImportRequestHash {

    private ImportRequestHash() {
    }

    static String of(ImportTimetableCommand command) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(command.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", impossible);
        }
    }
}
