package ua.vtc.rozklad.importer.ai;

/** A binary input for a vision call: a PDF or an image with its media type. */
public record AiDocument(String mediaType, byte[] content) {

    public static final String PDF = "application/pdf";

    public AiDocument {
        content = content.clone();
    }

    public boolean pdf() {
        return PDF.equals(mediaType);
    }

    @Override
    public byte[] content() {
        return content.clone();
    }

    public int size() {
        return content.length;
    }
}
