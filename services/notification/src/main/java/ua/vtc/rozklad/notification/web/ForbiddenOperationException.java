package ua.vtc.rozklad.notification.web;

/** 403 with a specific Ukrainian reason (ownership rules checked in services, not by URL). */
public class ForbiddenOperationException extends RuntimeException {

    public ForbiddenOperationException(String message) {
        super(message);
    }
}
