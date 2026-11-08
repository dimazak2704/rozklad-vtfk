package ua.vtc.rozklad.core.substitution.internal.change;

import java.util.ArrayList;
import java.util.List;
import ua.vtc.rozklad.core.shared.domain.ReasonCode;

/**
 * Which fields each kind of change needs (DOMAIN §7–§8) as a pure check that reports field
 * errors, so the dispatcher's form can highlight them (400 {@code errors[]}).
 */
public final class ChangeKindRules {

    private static final String NEEDS_ORIGINAL = "Оберіть заняття, яке змінюється";
    private static final String NEEDS_TEACHER = "Вкажіть викладача, який проведе пару";
    private static final String SINGLE_PAIR = "Діапазон пар можливий лише для блоку або додаткових пар";

    private ChangeKindRules() {
    }

    public record Violation(String field, String message) {
    }

    public static List<Violation> check(CreateChangeRequest request) {
        List<Violation> violations = new ArrayList<>();
        if (request.kind() == null) {
            return violations;
        }
        boolean range = request.pairFrom() != null && request.pairTo() != null
                && !request.pairTo().equals(request.pairFrom());
        if (request.pairFrom() != null && request.pairTo() != null && request.pairTo() < request.pairFrom()) {
            violations.add(new Violation("pairTo", "Остання пара не може бути раніше за першу"));
        }
        switch (request.kind()) {
            case REPLACE_TEACHER -> {
                require(request.originalLessonId(), "originalLessonId", NEEDS_ORIGINAL, violations);
                require(request.newTeacherId(), "newTeacherId", NEEDS_TEACHER, violations);
                singlePair(range, violations);
            }
            case REPLACE_DISCIPLINE -> {
                require(request.originalLessonId(), "originalLessonId", NEEDS_ORIGINAL, violations);
                require(request.newDisciplineId(), "newDisciplineId", "Вкажіть дисципліну, яка буде замість цієї пари",
                        violations);
                require(request.newTeacherId(), "newTeacherId", NEEDS_TEACHER, violations);
                singlePair(range, violations);
            }
            case SWAP -> {
                require(request.originalLessonId(), "originalLessonId", NEEDS_ORIGINAL, violations);
                require(request.swapLessonId(), "swapLessonId", "Вкажіть пару, яку переставляють", violations);
                forbid(request.newTeacherId(), "newTeacherId",
                        "Під час перестановки викладач береться з переставленої пари", violations);
                forbid(request.newDisciplineId(), "newDisciplineId",
                        "Під час перестановки дисципліна береться з переставленої пари", violations);
                singlePair(range, violations);
            }
            case MERGE_SUBGROUPS -> {
                require(request.originalLessonId(), "originalLessonId", NEEDS_ORIGINAL, violations);
                require(request.swapLessonId(), "swapLessonId", "Вкажіть пару іншої підгрупи, до якої приєднується ця",
                        violations);
                forbid(request.newTeacherId(), "newTeacherId",
                        "Під час об'єднання підгруп викладач береться з пари іншої підгрупи", violations);
                forbid(request.newDisciplineId(), "newDisciplineId",
                        "Під час об'єднання підгруп дисципліна лишається та сама", violations);
                singlePair(range, violations);
            }
            case CANCEL -> {
                require(request.originalLessonId(), "originalLessonId", NEEDS_ORIGINAL, violations);
                forbid(request.newTeacherId(), "newTeacherId", "Скасована пара не має нового викладача", violations);
                forbid(request.newDisciplineId(), "newDisciplineId", "Скасована пара не має нової дисципліни",
                        violations);
                forbid(request.newRoomId(), "newRoomId", "Скасована пара не має аудиторії", violations);
                singlePair(range, violations);
            }
            case ROOM_CHANGE -> {
                require(request.originalLessonId(), "originalLessonId", NEEDS_ORIGINAL, violations);
                require(request.newRoomId(), "newRoomId", "Вкажіть нову аудиторію", violations);
                forbid(request.newTeacherId(), "newTeacherId", "Заміна аудиторії не змінює викладача", violations);
                forbid(request.newDisciplineId(), "newDisciplineId", "Заміна аудиторії не змінює дисципліну",
                        violations);
                singlePair(range, violations);
            }
            case DISCIPLINE_FINISHED -> {
                require(request.originalLessonId(), "originalLessonId", NEEDS_ORIGINAL, violations);
                if (request.reasonCode() != null && request.reasonCode() != ReasonCode.FINISHED) {
                    violations.add(new Violation("reasonCode", "Для вичитаної дисципліни причина — «Вичит.»"));
                }
                if ((request.newDisciplineId() == null) != (request.newTeacherId() == null)) {
                    violations.add(new Violation(request.newDisciplineId() == null ? "newDisciplineId" : "newTeacherId",
                            "Вкажіть і дисципліну, і викладача, які будуть замість пари, або нічого"));
                }
                singlePair(range, violations);
            }
            case ADDITIONAL -> {
                forbid(request.originalLessonId(), "originalLessonId", "Додаткова пара не замінює наявне заняття",
                        violations);
                require(request.newDisciplineId(), "newDisciplineId", "Вкажіть дисципліну додаткової пари", violations);
                require(request.newTeacherId(), "newTeacherId", NEEDS_TEACHER, violations);
            }
            case BLOCK -> {
                forbid(request.originalLessonId(), "originalLessonId", "Блок замінює всі пари групи в діапазоні",
                        violations);
                require(request.newDisciplineId(), "newDisciplineId", "Вкажіть дисципліну блоку (наприклад, ЗУ)",
                        violations);
            }
        }
        return violations;
    }

    private static void require(Object value, String field, String message, List<Violation> violations) {
        if (value == null) {
            violations.add(new Violation(field, message));
        }
    }

    private static void forbid(Object value, String field, String message, List<Violation> violations) {
        if (value != null) {
            violations.add(new Violation(field, message));
        }
    }

    private static void singlePair(boolean range, List<Violation> violations) {
        if (range) {
            violations.add(new Violation("pairTo", SINGLE_PAIR));
        }
    }
}
