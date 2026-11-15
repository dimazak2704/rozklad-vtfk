package ua.vtc.rozklad.notification.email;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * E-mail addresses are personal data: logs and the admin log show them masked ({@code k•••@vtc.vn.ua}), only
 * the owner sees his own. Parsing is strict (one address, a domain, no display-name tricks), and the
 * verification policy of the channel lives here.
 */
public final class MailAddresses {

    private static final Pattern ANY_ADDRESS = Pattern.compile("[\\p{L}\\p{N}._%+'-]+@[\\p{L}\\p{N}.-]+\\.[\\p{L}]{2,}");
    private static final int MAX_LENGTH = 254;

    private MailAddresses() {
    }

    /** {@code k.mykola@vtc.vn.ua → k•••@vtc.vn.ua}; anything unreadable → {@code •••}. */
    public static String mask(String address) {
        if (address == null) {
            return null;
        }
        int at = address.lastIndexOf('@');
        if (at <= 0 || at == address.length() - 1) {
            return "•••";
        }
        return address.charAt(0) + "•••" + address.substring(at);
    }

    /** Masks every address inside a free text (an SMTP reply quotes the recipient). */
    public static String maskAll(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = ANY_ADDRESS.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(mask(matcher.group())));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * The address as one strictly parsed mailbox in lower case, or empty: blank, longer than 254, several
     * addresses, a display name, or no domain part.
     */
    public static Optional<String> normalize(String address) {
        if (address == null || address.isBlank() || address.length() > MAX_LENGTH) {
            return Optional.empty();
        }
        String trimmed = address.strip();
        if (trimmed.chars().anyMatch(c -> c < 0x20 || c == 0x7f || c == '<' || c == '>' || c == ',')) {
            return Optional.empty();
        }
        try {
            InternetAddress[] parsed = InternetAddress.parse(trimmed, true);
            if (parsed.length != 1 || parsed[0].getPersonal() != null) {
                return Optional.empty();
            }
            String plain = parsed[0].getAddress();
            int at = plain.lastIndexOf('@');
            if (at <= 0 || at == plain.length() - 1 || !plain.substring(at + 1).contains(".")) {
                return Optional.empty();
            }
            parsed[0].validate();
            return Optional.of(plain.toLowerCase(Locale.ROOT));
        } catch (AddressException e) {
            return Optional.empty();
        }
    }

    /** The domain part in lower case ({@code vtc.vn.ua}). */
    public static String domain(String address) {
        return address.substring(address.lastIndexOf('@') + 1).toLowerCase(Locale.ROOT);
    }

    /** A rate-limit key that does not store the address itself. */
    public static String fingerprint(String address) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(address.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** How the address of an account counts for the channel. */
    public enum Status {
        VERIFIED,
        UNVERIFIED,
        MISSING
    }

    /** Who vouches for the address. */
    public enum VerifiedBy {
        /** The account signs in with Google, which confirmed the address. */
        GOOGLE,
        /** The address is on a trusted domain (an admin typed the corporate address to grant access). */
        TRUSTED_DOMAIN
    }

    /** @param address the normalized address, null when missing or unreadable */
    public record Verification(String address, Status status, VerifiedBy verifiedBy) {

        public boolean verified() {
            return status == Status.VERIFIED;
        }
    }

    /**
     * The verification policy: a readable address is verified when the account has a Google binding, or when
     * its domain is one of {@code trustedDomains}; otherwise it gets no letters.
     */
    public static Verification verify(String rawAddress, boolean googleLinked, Collection<String> trustedDomains) {
        Optional<String> address = normalize(rawAddress);
        if (address.isEmpty()) {
            return new Verification(null, Status.MISSING, null);
        }
        if (googleLinked) {
            return new Verification(address.get(), Status.VERIFIED, VerifiedBy.GOOGLE);
        }
        if (trustedDomains.contains(domain(address.get()))) {
            return new Verification(address.get(), Status.VERIFIED, VerifiedBy.TRUSTED_DOMAIN);
        }
        return new Verification(address.get(), Status.UNVERIFIED, null);
    }
}
