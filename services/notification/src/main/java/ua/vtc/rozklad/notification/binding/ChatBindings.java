package ua.vtc.rozklad.notification.binding;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.notification.bot.BotUpdate.ChatRef;
import ua.vtc.rozklad.notification.config.TelegramProperties;
import ua.vtc.rozklad.notification.core.CoreDtos.PublicGroup;
import ua.vtc.rozklad.notification.shared.ChatIds;
import ua.vtc.rozklad.notification.web.BusinessRuleException;
import ua.vtc.rozklad.notification.web.NotFoundException;

/** Chat bindings: who receives what. The single source for "may the bot write to this chat". */
@Service
public class ChatBindings {

    static final String OWNER_CHAT_TITLE = "Чат власника бота";

    private static final Logger log = LoggerFactory.getLogger(ChatBindings.class);

    private final ChatBindingRepository repository;
    private final TelegramProperties telegram;
    private final Clock clock;

    public ChatBindings(ChatBindingRepository repository, TelegramProperties telegram, Clock clock) {
        this.repository = repository;
        this.telegram = telegram;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ChatBinding> list() {
        return repository.findAllForListing();
    }

    @Transactional
    public void delete(UUID id) {
        ChatBinding binding = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Прив’язку не знайдено."));
        repository.delete(binding);
        log.info("Binding {} {} of chat {} deleted", binding.getType(), id, ChatIds.mask(binding.getChatId()));
    }

    /** Owner chat (TELEGRAM_OWNER_CHAT_ID) as ADMIN_CHAT — dev convenience, idempotent. */
    @Transactional
    public void ensureOwnerAdminChat(long ownerChatId) {
        if (repository.findByChatIdAndType(ownerChatId, BindingType.ADMIN_CHAT).isEmpty()) {
            repository.save(ChatBinding.adminChat(ownerChatId, OWNER_CHAT_TITLE, now()));
            log.info("Owner chat {} registered as ADMIN_CHAT", ChatIds.mask(ownerChatId));
        }
    }

    /** The bot may write to the owner chat and to chats bound through the system. */
    @Transactional(readOnly = true)
    public boolean isKnownChat(long chatId) {
        return telegram.isOwnerChat(chatId) || repository.existsByChatId(chatId);
    }

    @Transactional(readOnly = true)
    public Optional<ChatBinding> find(long chatId, BindingType type) {
        return repository.findByChatIdAndType(chatId, type);
    }

    @Transactional(readOnly = true)
    public Optional<ChatBinding> subscription(long chatId) {
        return find(chatId, BindingType.SUBSCRIBER);
    }

    @Transactional(readOnly = true)
    public Optional<ChatBinding> teacherBinding(long chatId) {
        return find(chatId, BindingType.TEACHER);
    }

    @Transactional(readOnly = true)
    public Optional<ChatBinding> groupChat(long chatId) {
        return find(chatId, BindingType.GROUP_CHAT);
    }

    @Transactional(readOnly = true)
    public Optional<ChatBinding> teacherChat(UUID teacherId) {
        return repository.findByTypeAndTeacherId(BindingType.TEACHER, teacherId);
    }

    @Transactional(readOnly = true)
    public List<ChatBinding> groupChatsFor(Collection<UUID> groupIds) {
        return groupIds.isEmpty() ? List.of() : repository.findByTypeAndGroupIdIn(BindingType.GROUP_CHAT, groupIds);
    }

    @Transactional(readOnly = true)
    public List<ChatBinding> teacherChatsFor(Collection<UUID> teacherIds) {
        return teacherIds.isEmpty() ? List.of()
                : repository.findByTypeAndTeacherIdIn(BindingType.TEACHER, teacherIds);
    }

    @Transactional(readOnly = true)
    public List<ChatBinding> adminChats() {
        return repository.findByType(BindingType.ADMIN_CHAT);
    }

    /** Subscribers and group chats with the morning schedule on (one query). */
    @Transactional(readOnly = true)
    public List<ChatBinding> digestSubscribers() {
        return repository.findByTypeInAndDigestEnabledTrue(List.of(BindingType.SUBSCRIBER, BindingType.GROUP_CHAT));
    }

    /** «Моя група» in a private chat: subscribe or switch the group (digest stays as chosen). */
    @Transactional
    public ChatBinding subscribe(long chatId, PublicGroup group) {
        Optional<ChatBinding> existing = repository.findByChatIdAndType(chatId, BindingType.SUBSCRIBER);
        if (existing.isPresent()) {
            ChatBinding binding = existing.get();
            binding.retarget(group.id(), group.code(), ChatBinding.subscriberTitle(group.code()), now());
            return binding;
        }
        return repository.save(ChatBinding.subscriber(chatId, group.id(), group.code(), now()));
    }

    /** @return false when the chat has no subscription */
    @Transactional
    public boolean setDigest(long chatId, boolean enabled) {
        Optional<ChatBinding> subscription = repository.findByChatIdAndType(chatId, BindingType.SUBSCRIBER);
        subscription.ifPresent(binding -> binding.setDigestEnabled(enabled, now()));
        return subscription.isPresent();
    }

    /**
     * {@code /rozklad_on}, {@code /rozklad_off} in a group chat (the caller checked that the author is a chat admin).
     *
     * @return the group chat binding, empty when the chat is not bound to a group
     */
    @Transactional
    public Optional<ChatBinding> setGroupChatDigest(long chatId, boolean enabled) {
        Optional<ChatBinding> groupChat = repository.findByChatIdAndType(chatId, BindingType.GROUP_CHAT);
        groupChat.ifPresent(binding -> {
            if (binding.isDigestEnabled() != enabled) {
                binding.setDigestEnabled(enabled, now());
                log.info("Morning schedule of group chat {} switched {}", ChatIds.mask(chatId), enabled ? "on" : "off");
            }
        });
        return groupChat;
    }

    /**
     * The admin's switch in the web panel ({@code PATCH /api/notify/bindings/{id}}).
     *
     * @throws NotFoundException unknown binding
     * @throws BusinessRuleException {@code digest-not-supported} for teacher and admin chats
     */
    @Transactional
    public ChatBinding setDigest(UUID id, boolean enabled) {
        ChatBinding binding = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Прив’язку не знайдено."));
        if (!binding.getType().canGetDigest()) {
            throw new BusinessRuleException("digest-not-supported",
                    "Ранковий розклад надсилається лише в чати груп і підписникам.");
        }
        if (binding.isDigestEnabled() != enabled) {
            binding.setDigestEnabled(enabled, now());
            log.info("Morning schedule of {} {} switched {} in the web panel", binding.getType(), id,
                    enabled ? "on" : "off");
        }
        return binding;
    }

    /** Applies a redeemed code to a chat (replaces an existing binding of the same type). */
    @Transactional
    public ChatBinding bind(BindingTarget target, ChatRef chat) {
        Instant now = now();
        if (target.type() == BindingType.TEACHER) {
            // викладач отримує запити лише в одному чаті: прив'язка нового чату прибирає старий
            repository.deleteTeacherBindingsElsewhere(BindingType.TEACHER, target.teacherId(), chat.id());
        }
        Optional<ChatBinding> existing = repository.findByChatIdAndType(chat.id(), target.type());
        ChatBinding binding = switch (target.type()) {
            case GROUP_CHAT -> existing.map(found -> {
                found.retarget(target.groupId(), target.groupCode(), groupChatTitle(chat, target), now);
                return found;
            }).orElseGet(() -> ChatBinding.groupChat(chat.id(), target.groupId(), target.groupCode(),
                    groupChatTitle(chat, target), now));
            case SUBSCRIBER -> existing.map(found -> {
                found.retarget(target.groupId(), target.groupCode(),
                        ChatBinding.subscriberTitle(target.groupCode()), now);
                return found;
            }).orElseGet(() -> ChatBinding.subscriber(chat.id(), target.groupId(), target.groupCode(), now));
            case TEACHER -> existing.map(found -> {
                found.reassignTeacher(target.teacherId(), target.label(), now);
                return found;
            }).orElseGet(() -> ChatBinding.teacher(chat.id(), target.teacherId(), target.label(), now));
            case ADMIN_CHAT -> existing.map(found -> {
                found.rename(adminChatTitle(chat), now);
                return found;
            }).orElseGet(() -> ChatBinding.adminChat(chat.id(), adminChatTitle(chat), now));
        };
        ChatBinding saved = repository.save(binding);
        log.info("Chat {} bound as {} ({})", ChatIds.mask(chat.id()), target.type(), target.label());
        return saved;
    }

    /** The bot was removed from the chat or blocked: nothing can be delivered there any more. */
    @Transactional
    public int forgetChat(long chatId) {
        int removed = repository.deleteByChatId(chatId);
        if (removed > 0) {
            log.info("Bot left chat {}: {} binding(s) removed", ChatIds.mask(chatId), removed);
        }
        return removed;
    }

    /** A group became a supergroup and got a new id. */
    @Transactional
    public void migrateChat(long fromChatId, long toChatId) {
        int moved = repository.migrateChat(fromChatId, toChatId);
        if (moved > 0) {
            log.info("Chat {} migrated to {}: {} binding(s) moved", ChatIds.mask(fromChatId),
                    ChatIds.mask(toChatId), moved);
        }
    }

    private String groupChatTitle(ChatRef chat, BindingTarget target) {
        String chatTitle = chat.title() == null || chat.title().isBlank() ? "Чат групи" : chat.title();
        return chatTitle + " → " + target.groupCode();
    }

    private String adminChatTitle(ChatRef chat) {
        if (telegram.isOwnerChat(chat.id())) {
            return OWNER_CHAT_TITLE;
        }
        // імена людей з особистих чатів не зберігаємо
        return chat.isPrivate() || chat.title() == null || chat.title().isBlank()
                ? "Службовий чат (особистий)"
                : chat.title();
    }

    private Instant now() {
        return clock.instant();
    }
}
