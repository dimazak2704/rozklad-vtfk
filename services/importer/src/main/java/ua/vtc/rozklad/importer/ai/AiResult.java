package ua.vtc.rozklad.importer.ai;

/** A parsed structured answer together with the usage of the call that produced it. */
public record AiResult<T>(T value, AiCallInfo call) {
}
