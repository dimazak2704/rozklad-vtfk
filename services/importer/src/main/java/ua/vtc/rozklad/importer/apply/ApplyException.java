package ua.vtc.rozklad.importer.apply;

/** The draft cannot be applied as it is (missing semester, groups, date…); Ukrainian message for the admin. */
public class ApplyException extends RuntimeException {

    private final String code;

    public ApplyException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
