package ua.vtc.rozklad.importer.job;

/** File format recognized by content (magic bytes), never by the file name. */
public enum FileType {
    XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "Excel (XLSX)"),
    DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "Word (DOCX)"),
    DOC("application/msword", "Word 97–2003 (DOC)"),
    PDF("application/pdf", "PDF"),
    JPEG("image/jpeg", "зображення JPEG"),
    PNG("image/png", "зображення PNG"),
    WEBP("image/webp", "зображення WEBP"),
    GIF("image/gif", "зображення GIF");

    private final String mediaType;
    private final String label;

    FileType(String mediaType, String label) {
        this.mediaType = mediaType;
        this.label = label;
    }

    public String mediaType() {
        return mediaType;
    }

    /** Ukrainian name for messages. */
    public String label() {
        return label;
    }

    public boolean isImage() {
        return this == JPEG || this == PNG || this == WEBP || this == GIF;
    }
}
