package ua.vtc.rozklad.importer.job;

import java.util.UUID;

/** Published inside the transaction that queued work; processed after commit on the import executor. */
public record ImportJobQueued(UUID jobId, Stage stage) {

    public enum Stage {
        PARSE, APPLY
    }
}
