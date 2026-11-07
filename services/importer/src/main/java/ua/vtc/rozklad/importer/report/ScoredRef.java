package ua.vtc.rozklad.importer.report;

import java.util.UUID;

/** A catalog entity with a match score 0..1 (CONTRACTS §5 {@code {id,label,score}}). */
public record ScoredRef(UUID id, String label, double score) {

    public ScoredRef {
        score = Math.round(score * 1000) / 1000.0;
    }
}
