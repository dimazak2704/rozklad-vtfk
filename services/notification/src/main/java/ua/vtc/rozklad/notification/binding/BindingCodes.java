package ua.vtc.rozklad.notification.binding;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.notification.bot.BotUpdate.ChatRef;
import ua.vtc.rozklad.notification.config.BotProperties;
import ua.vtc.rozklad.notification.config.TelegramProperties;
import ua.vtc.rozklad.notification.shared.ChatIds;

/**
 * One-time binding codes (TTL 24 h): issued in the web panel, redeemed in Telegram with
 * {@code /start <code>} (deep link) or {@code /bind <code>} (group chat).
 */
@Service
public class BindingCodes {

    /** No 0/O, 1/I: codes are also typed by hand. 32 symbols × 8 = 40 bits. */
    static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    static final int LENGTH = 8;

    private static final Logger log = LoggerFactory.getLogger(BindingCodes.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final BindingCodeRepository codes;
    private final ChatBindings bindings;
    private final TelegramProperties telegram;
    private final BotProperties bot;
    private final Clock clock;

    public BindingCodes(BindingCodeRepository codes, ChatBindings bindings, TelegramProperties telegram,
                        BotProperties bot, Clock clock) {
        this.codes = codes;
        this.bindings = bindings;
        this.telegram = telegram;
        this.bot = bot;
        this.clock = clock;
    }

    @Transactional
    public IssuedCode issue(BindingTarget target, String createdBy) {
        Instant now = clock.instant();
        String code = newCode();
        BindingCode stored = codes.save(new BindingCode(hash(code), target, createdBy, now, now.plus(bot.codeTtl())));
        log.info("Binding code {} issued by {} for {}", target.type(), createdBy, target.label());
        boolean groupCapable = target.type() == BindingType.GROUP_CHAT || target.type() == BindingType.ADMIN_CHAT;
        return new IssuedCode(display(code),
                telegram.botLink() + "?start=" + code,
                groupCapable ? telegram.botLink() + "?startgroup=" + code : null,
                groupCapable ? "/bind " + display(code) : null,
                stored.getExpiresAt(), target);
    }

    /**
     * Binds the chat when the code is valid for it. A code that does not fit the chat type is not
     * spent, so it can still be used in the right chat.
     */
    @Transactional
    public Redemption redeem(String rawCode, ChatRef chat) {
        Optional<String> normalized = normalize(rawCode);
        if (normalized.isEmpty()) {
            return new Redemption.Rejected(RejectReason.UNKNOWN, null);
        }
        Optional<BindingCode> found = codes.findForRedeem(hash(normalized.get()));
        if (found.isEmpty()) {
            return new Redemption.Rejected(RejectReason.UNKNOWN, null);
        }
        BindingCode code = found.get();
        Instant now = clock.instant();
        if (code.isUsed()) {
            return new Redemption.Rejected(RejectReason.USED, code.getType());
        }
        if (code.isExpired(now)) {
            return new Redemption.Rejected(RejectReason.EXPIRED, code.getType());
        }
        if (!code.getType().fitsChat(chat.kind())) {
            if (code.getType() == BindingType.TEACHER && chat.isGroup()) {
                // код викладача побачили всі учасники групового чату — анулюємо, щоб ніхто не прив'язав себе
                code.markUsed(chat.id(), now);
                log.warn("Teacher binding code posted in group chat {}: code burned", ChatIds.mask(chat.id()));
                return new Redemption.Rejected(RejectReason.BURNED, code.getType());
            }
            return new Redemption.Rejected(RejectReason.WRONG_CHAT, code.getType());
        }
        code.markUsed(chat.id(), now);
        ChatBinding binding = bindings.bind(code.target(), chat);
        log.info("Binding code {} redeemed in chat {}", code.getType(), ChatIds.mask(chat.id()));
        return new Redemption.Bound(binding, code.target());
    }

    /** Is there any code with this text (used/expired included)? Decides whether to answer an unknown chat. */
    @Transactional(readOnly = true)
    public boolean exists(String rawCode) {
        return normalize(rawCode).map(code -> codes.existsByCodeHash(hash(code))).orElse(false);
    }

    @Transactional
    public int purgeExpired(Instant before) {
        return codes.deleteExpiredBefore(before);
    }

    static String newCode() {
        StringBuilder code = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }

    /** {@code " k7qx-m2pa "} → {@code K7QXM2PA}; empty when the text cannot be a code. */
    static Optional<String> normalize(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String code = raw.toUpperCase(Locale.ROOT).replaceAll("[\\s\\-_]", "");
        if (code.length() != LENGTH) {
            return Optional.empty();
        }
        for (char c : code.toCharArray()) {
            if (ALPHABET.indexOf(c) < 0) {
                return Optional.empty();
            }
        }
        return Optional.of(code);
    }

    static String display(String code) {
        return code.substring(0, LENGTH / 2) + "-" + code.substring(LENGTH / 2);
    }

    static String hash(String normalizedCode) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalizedCode.getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** Result of {@link #issue}. {@code groupLink}/{@code command} only for chat-capable types. */
    public record IssuedCode(String code, String deepLink, String groupLink, String command, Instant expiresAt,
                             BindingTarget target) {
    }

    public enum RejectReason {
        UNKNOWN,
        USED,
        EXPIRED,
        WRONG_CHAT,
        /** A teacher code shown in a group chat: spent so nobody else can use it. */
        BURNED
    }

    public sealed interface Redemption {

        record Bound(ChatBinding binding, BindingTarget target) implements Redemption {
        }

        /** {@code codeType} is null for an unknown code. */
        record Rejected(RejectReason reason, BindingType codeType) implements Redemption {
        }
    }
}
