package ua.vtc.rozklad.scheduler.web;

/** 404 with a Ukrainian message shown to the user. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
