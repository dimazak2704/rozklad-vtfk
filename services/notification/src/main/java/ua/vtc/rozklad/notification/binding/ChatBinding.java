package ua.vtc.rozklad.notification.binding;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import ua.vtc.rozklad.notification.shared.IdentifiableEntity;

/**
 * A Telegram chat bound through the system. One binding per (chat, type); a teacher has at most
 * one TEACHER chat. The bot writes only to bound chats and the owner chat.
 */
@Entity
@Table(name = "chat_binding")
public class ChatBinding extends IdentifiableEntity {

    @Column(name = "chat_id", nullable = false)
    private long chatId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private BindingType type;

    @Column(name = "group_id")
    private UUID groupId;

    @Column(name = "group_code", length = 20)
    private String groupCode;

    @Column(name = "teacher_id")
    private UUID teacherId;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "digest_enabled", nullable = false)
    private boolean digestEnabled;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected ChatBinding() {
    }

    private ChatBinding(long chatId, BindingType type, Instant now) {
        this.chatId = chatId;
        this.type = Objects.requireNonNull(type, "type");
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** The morning schedule of a group chat is opt-in ({@code /rozklad_on}): a new group chat starts without it. */
    public static ChatBinding groupChat(long chatId, UUID groupId, String groupCode, String title, Instant now) {
        ChatBinding binding = new ChatBinding(chatId, BindingType.GROUP_CHAT, now);
        binding.retarget(groupId, groupCode, title, now);
        binding.digestEnabled = false;
        return binding;
    }

    public static ChatBinding subscriber(long chatId, UUID groupId, String groupCode, Instant now) {
        ChatBinding binding = new ChatBinding(chatId, BindingType.SUBSCRIBER, now);
        binding.retarget(groupId, groupCode, subscriberTitle(groupCode), now);
        binding.digestEnabled = true;
        return binding;
    }

    public static ChatBinding teacher(long chatId, UUID teacherId, String teacherName, Instant now) {
        ChatBinding binding = new ChatBinding(chatId, BindingType.TEACHER, now);
        binding.teacherId = Objects.requireNonNull(teacherId, "teacherId");
        binding.title = requireTitle(teacherName);
        return binding;
    }

    public static ChatBinding adminChat(long chatId, String title, Instant now) {
        ChatBinding binding = new ChatBinding(chatId, BindingType.ADMIN_CHAT, now);
        binding.title = requireTitle(title);
        return binding;
    }

    static String subscriberTitle(String groupCode) {
        return "Підписник · " + groupCode;
    }

    /** GROUP_CHAT / SUBSCRIBER: follow another group. */
    public void retarget(UUID groupId, String groupCode, String title, Instant now) {
        if (!type.needsGroup()) {
            throw new IllegalStateException(type + " is not bound to a group");
        }
        this.groupId = Objects.requireNonNull(groupId, "groupId");
        this.groupCode = Objects.requireNonNull(groupCode, "groupCode");
        this.title = requireTitle(title);
        this.updatedAt = now;
    }

    public void reassignTeacher(UUID teacherId, String teacherName, Instant now) {
        if (type != BindingType.TEACHER) {
            throw new IllegalStateException(type + " is not a teacher binding");
        }
        this.teacherId = Objects.requireNonNull(teacherId, "teacherId");
        this.title = requireTitle(teacherName);
        this.updatedAt = now;
    }

    public void rename(String title, Instant now) {
        this.title = requireTitle(title);
        this.updatedAt = now;
    }

    public void setDigestEnabled(boolean enabled, Instant now) {
        if (!type.canGetDigest()) {
            throw new IllegalStateException(type + " does not receive the morning digest");
        }
        this.digestEnabled = enabled;
        this.updatedAt = now;
    }

    private static String requireTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("binding title is empty");
        }
        return title.length() > 200 ? title.substring(0, 200) : title;
    }

    public long getChatId() {
        return chatId;
    }

    public BindingType getType() {
        return type;
    }

    public UUID getGroupId() {
        return groupId;
    }

    public String getGroupCode() {
        return groupCode;
    }

    public UUID getTeacherId() {
        return teacherId;
    }

    public String getTitle() {
        return title;
    }

    public boolean isDigestEnabled() {
        return digestEnabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
