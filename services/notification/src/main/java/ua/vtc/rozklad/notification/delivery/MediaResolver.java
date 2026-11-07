package ua.vtc.rozklad.notification.delivery;

/**
 * Turns a media reference of a queued PHOTO (e.g. {@code order-png:<orderId>}) into bytes at send
 * time, so the queue does not store copies of the same picture for every chat.
 */
public interface MediaResolver {

    /**
     * @throws MediaUnavailableException when the media can never be produced (no converter, unknown order)
     * @throws RuntimeException          on a failure that may pass (the message is retried)
     */
    Media resolve(String mediaRef);

    record Media(byte[] bytes, String fileName) {
    }

    /** Permanent: retrying will not help. */
    class MediaUnavailableException extends RuntimeException {

        public MediaUnavailableException(String message) {
            super(message);
        }
    }
}
