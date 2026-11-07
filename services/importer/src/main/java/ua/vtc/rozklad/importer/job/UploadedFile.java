package ua.vtc.rozklad.importer.job;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Metadata of an accepted upload; {@code type} is sniffed from the content. */
public record UploadedFile(String fileName, String contentType, FileType type, long size, String sha256) {

    public static UploadedFile of(String fileName, String contentType, FileType type, byte[] content) {
        return new UploadedFile(fileName, contentType, type, content.length, sha256(content));
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
