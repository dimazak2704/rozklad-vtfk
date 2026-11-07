package ua.vtc.rozklad.importer.storage;

/** The bytes of a job's file are gone (deleted volume, manual cleanup). */
public class StoredFileMissingException extends RuntimeException {

    public StoredFileMissingException(String message) {
        super(message);
    }
}
