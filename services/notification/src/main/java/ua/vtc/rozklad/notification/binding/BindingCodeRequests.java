package ua.vtc.rozklad.notification.binding;

import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import ua.vtc.rozklad.notification.core.CoreClient;
import ua.vtc.rozklad.notification.core.CoreDtos.CurrentUser;
import ua.vtc.rozklad.notification.core.CoreDtos.PublicGroup;
import ua.vtc.rozklad.notification.core.CoreDtos.PublicTeacher;
import ua.vtc.rozklad.notification.core.CoreQueries;
import ua.vtc.rozklad.notification.web.BusinessRuleException;
import ua.vtc.rozklad.notification.web.FieldValidationException;
import ua.vtc.rozklad.notification.web.ForbiddenOperationException;
import ua.vtc.rozklad.notification.web.NotFoundException;

/**
 * {@code POST /api/notify/bindings/codes}: validates the target against core and the caller's
 * rights, then issues a code. ADMIN: any type. TEACHER: only a TEACHER code for himself — the
 * teacher is resolved from his own token ({@code GET /api/me} on core), never trusted from the body.
 */
@Service
public class BindingCodeRequests {

    private final BindingCodes codes;
    private final CoreQueries queries;
    private final CoreClient core;

    public BindingCodeRequests(BindingCodes codes, CoreQueries queries, CoreClient core) {
        this.codes = codes;
        this.queries = queries;
        this.core = core;
    }

    public BindingCodes.IssuedCode create(BindingType type, UUID groupId, UUID teacherId, Caller caller) {
        BindingTarget target = caller.isAdmin() ? adminTarget(type, groupId, teacherId) : teacherSelf(type, teacherId,
                caller);
        return codes.issue(target, caller.username());
    }

    private BindingTarget adminTarget(BindingType type, UUID groupId, UUID teacherId) {
        return switch (type) {
            case GROUP_CHAT, SUBSCRIBER -> {
                if (groupId == null) {
                    throw new FieldValidationException("groupId", "Оберіть групу.");
                }
                PublicGroup group = queries.group(groupId)
                        .orElseThrow(() -> new NotFoundException("Групу не знайдено."));
                yield BindingTarget.group(type, group.id(), group.code());
            }
            case TEACHER -> {
                if (teacherId == null) {
                    throw new FieldValidationException("teacherId", "Оберіть викладача.");
                }
                PublicTeacher teacher = queries.teacher(teacherId)
                        .orElseThrow(() -> new NotFoundException("Викладача не знайдено."));
                yield BindingTarget.teacher(teacher.id(), teacher.label());
            }
            case ADMIN_CHAT -> BindingTarget.adminChat();
        };
    }

    private BindingTarget teacherSelf(BindingType type, UUID requestedTeacherId, Caller caller) {
        if (type != BindingType.TEACHER) {
            throw new ForbiddenOperationException("Викладач може створити код лише для власного Telegram.");
        }
        CurrentUser me = core.currentUser(caller.bearerToken());
        if (me == null || me.teacher() == null || me.teacher().id() == null) {
            throw new BusinessRuleException("teacher-not-linked",
                    "Ваш обліковий запис не пов’язаний з викладачем. Зверніться до диспетчера.");
        }
        if (requestedTeacherId != null && !requestedTeacherId.equals(me.teacher().id())) {
            throw new ForbiddenOperationException("Можна створити код лише для власного Telegram.");
        }
        return BindingTarget.teacher(me.teacher().id(), me.teacher().label());
    }

    /** Authenticated caller of the web API. */
    public record Caller(String username, Set<String> roles, String bearerToken) {

        public boolean isAdmin() {
            return roles.contains("ADMIN");
        }
    }
}
