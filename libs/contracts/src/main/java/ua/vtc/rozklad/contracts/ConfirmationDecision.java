package ua.vtc.rozklad.contracts;

/** Teacher answer to a substitution request ({@code POST /api/internal/changes/{id}/reply}). */
public enum ConfirmationDecision {
    CONFIRM,
    DECLINE
}
