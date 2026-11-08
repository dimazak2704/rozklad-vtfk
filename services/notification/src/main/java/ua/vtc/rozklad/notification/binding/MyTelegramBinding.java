package ua.vtc.rozklad.notification.binding;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import ua.vtc.rozklad.notification.core.CoreClient;
import ua.vtc.rozklad.notification.core.CoreDtos.CurrentUser;
import ua.vtc.rozklad.notification.web.BusinessRuleException;

/**
 * {@code GET /api/notify/bindings/me}: whether the signed-in teacher's own Telegram chat is bound.
 * The teacher is resolved from the caller's token ({@code GET /api/me} on core), like the binding
 * codes a teacher creates for himself; other people's bindings stay ADMIN-only.
 */
@Service
public class MyTelegramBinding {

    private final CoreClient core;
    private final ChatBindings bindings;

    public MyTelegramBinding(CoreClient core, ChatBindings bindings) {
        this.core = core;
        this.bindings = bindings;
    }

    /** @throws BusinessRuleException {@code teacher-not-linked} (422) for an account without a teacher */
    public Status of(String bearerToken) {
        CurrentUser me = core.currentUser(bearerToken);
        if (me == null || me.teacher() == null || me.teacher().id() == null) {
            throw new BusinessRuleException("teacher-not-linked",
                    "Ваш обліковий запис не пов’язаний з викладачем. Зверніться до диспетчера.");
        }
        return new Status(me.teacher().id(), me.teacher().label(), bindings.teacherChat(me.teacher().id()));
    }

    public record Status(UUID teacherId, String teacherName, Optional<ChatBinding> binding) {
    }
}
