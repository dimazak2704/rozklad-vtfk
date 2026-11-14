package ua.vtc.rozklad.notification.binding;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ua.vtc.rozklad.notification.core.CoreClient;
import ua.vtc.rozklad.notification.core.CoreClient.TeacherStatus;
import ua.vtc.rozklad.notification.core.CoreDtos.PublicTeacher;
import ua.vtc.rozklad.notification.core.UpstreamRejectedException;
import ua.vtc.rozklad.notification.core.UpstreamUnavailableException;
import ua.vtc.rozklad.notification.shared.ChatIds;

/**
 * Offboarding: a teacher's Telegram stays bound to the catalog teacher, not to the account, so a teacher who left
 * (made inactive or removed in the catalog) would keep getting what is addressed to him and could still report
 * absences through the bot. Every night the teacher chats are compared with core's active teachers; a teacher
 * missing from that list is asked about one by one ({@code GET /api/internal/teachers/{id}}), and only a teacher
 * core reports inactive or unknown loses the chat. A short or failed list never unlinks anyone by itself.
 */
@Service
public class InactiveTeacherChats {

    private static final Logger log = LoggerFactory.getLogger(InactiveTeacherChats.class);

    private final ChatBindingRepository repository;
    private final ChatBindings bindings;
    private final CoreClient core;

    public InactiveTeacherChats(ChatBindingRepository repository, ChatBindings bindings, CoreClient core) {
        this.repository = repository;
        this.bindings = bindings;
        this.core = core;
    }

    @Scheduled(cron = "0 20 3 * * *", zone = "Europe/Kyiv")
    public void nightly() {
        try {
            unlinkInactive();
        } catch (RuntimeException e) {
            log.error("Check of inactive teachers' Telegram chats failed; it runs again tomorrow", e);
        }
    }

    /** @return how many teacher chats were unlinked */
    public int unlinkInactive() {
        List<ChatBinding> teacherChats = repository.findByType(BindingType.TEACHER);
        if (teacherChats.isEmpty()) {
            return 0;
        }
        Set<UUID> active = core.teachers().stream().map(PublicTeacher::id).collect(Collectors.toSet());
        int unlinked = 0;
        for (ChatBinding chat : teacherChats) {
            UUID teacherId = chat.getTeacherId();
            if (active.contains(teacherId)) {
                continue;
            }
            TeacherStatus status;
            try {
                status = core.teacherStatus(teacherId);
            } catch (UpstreamUnavailableException | UpstreamRejectedException e) {
                log.warn("Teacher {} of chat {} not checked: {}", teacherId, ChatIds.mask(chat.getChatId()),
                        e.getMessage());
                continue;
            }
            if (status != TeacherStatus.ACTIVE && bindings.unlinkTeacher(teacherId).isPresent()) {
                log.info("Telegram chat {} of {} teacher {} unlinked", ChatIds.mask(chat.getChatId()),
                        status == TeacherStatus.GONE ? "removed" : "inactive", teacherId);
                unlinked++;
            }
        }
        return unlinked;
    }
}
