package ua.vtc.rozklad.scheduler.solver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import ua.vtc.rozklad.contracts.events.ProblemSnapshot;
import ua.vtc.rozklad.scheduler.solver.domain.StudentGroup;
import ua.vtc.rozklad.scheduler.solver.domain.Timeslot;

/**
 * Checks a {@link ProblemSnapshot} before solving and lists every problem in Ukrainian (they reach
 * the dispatcher as the error of a FAILED job). The snapshot comes from core, so a broken
 * reference is a data error that must be fixed there, not guessed here.
 */
final class ProblemSnapshotValidator {

    private ProblemSnapshotValidator() {
    }

    static List<String> validate(ProblemSnapshot snapshot) {
        List<String> errors = new ArrayList<>();
        Set<Timeslot> timeslots = validateTimeslots(snapshot, errors);
        Map<Integer, List<ProblemSnapshot.ShiftWindow>> windows = validateShiftWindows(snapshot, errors);
        Map<UUID, ProblemSnapshot.Group> groups = validateGroups(snapshot, timeslots, windows, errors);
        Set<UUID> rooms = validateRooms(snapshot, errors);
        Set<UUID> teachers = validateTeachers(snapshot, errors);
        validateUnits(snapshot, timeslots, groups, rooms, teachers, errors);
        return errors;
    }

    private static Set<Timeslot> validateTimeslots(ProblemSnapshot snapshot, List<String> errors) {
        Set<Timeslot> result = new HashSet<>();
        if (snapshot.timeslots().isEmpty()) {
            errors.add("Не задано жодного слоту розкладу (день і пара).");
        }
        for (ProblemSnapshot.Timeslot slot : snapshot.timeslots()) {
            if (isValidSlot(slot.weekday(), slot.pair())) {
                result.add(new Timeslot(slot.weekday(), slot.pair()));
            } else {
                errors.add("Слот «%s» поза межами: дні 1–%d, пари 1–%d."
                        .formatted(slotLabel(slot.weekday(), slot.pair()), Timeslot.MAX_WEEKDAY, Timeslot.MAX_PAIR));
            }
        }
        return result;
    }

    private static Map<Integer, List<ProblemSnapshot.ShiftWindow>> validateShiftWindows(ProblemSnapshot snapshot,
                                                                                        List<String> errors) {
        Map<Integer, List<ProblemSnapshot.ShiftWindow>> byShift = new HashMap<>();
        Set<String> seen = new HashSet<>();
        for (ProblemSnapshot.ShiftWindow window : snapshot.shiftWindows()) {
            boolean validDay = window.weekday() >= 1 && window.weekday() <= Timeslot.MAX_WEEKDAY;
            boolean validPairs = window.firstPair() >= 1 && window.lastPair() <= Timeslot.MAX_PAIR
                    && window.firstPair() <= window.lastPair();
            if (!validDay || !validPairs) {
                errors.add("Вікно зміни %d на день %d має некоректні пари %d–%d."
                        .formatted(window.shift(), window.weekday(), window.firstPair(), window.lastPair()));
            } else if (!seen.add(window.shift() + "/" + window.weekday())) {
                errors.add("Для зміни %d день %d задано більше одного вікна.".formatted(window.shift(), window.weekday()));
            } else {
                byShift.computeIfAbsent(window.shift(), shift -> new ArrayList<>()).add(window);
            }
        }
        return byShift;
    }

    private static Map<UUID, ProblemSnapshot.Group> validateGroups(ProblemSnapshot snapshot, Set<Timeslot> timeslots,
                                                                   Map<Integer, List<ProblemSnapshot.ShiftWindow>> windows,
                                                                   List<String> errors) {
        Map<UUID, ProblemSnapshot.Group> groups = new HashMap<>();
        for (ProblemSnapshot.Group group : snapshot.groups()) {
            if (groups.putIfAbsent(group.id(), group) != null) {
                errors.add("Група %s повторюється у знімку.".formatted(group.code()));
                continue;
            }
            if (group.size() < 0) {
                errors.add("Група %s має від'ємну кількість студентів.".formatted(group.code()));
            }
            Set<Integer> subgroups = new HashSet<>(group.subgroups());
            if (subgroups.size() != group.subgroups().size() || group.subgroups().stream().anyMatch(n -> n == null || n < 1)) {
                errors.add("Група %s має некоректні номери підгруп %s.".formatted(group.code(), group.subgroups()));
            } else if (subgroups.size() > StudentGroup.MAX_COHORTS) {
                errors.add("Група %s має понад %d підгруп.".formatted(group.code(), StudentGroup.MAX_COHORTS));
            }
            List<ProblemSnapshot.ShiftWindow> shiftWindows = windows.get(group.shift());
            if (shiftWindows == null) {
                errors.add("Для зміни %d (група %s) не задано вікон занять.".formatted(group.shift(), group.code()));
            } else if (timeslots.stream().noneMatch(slot -> inAnyWindow(slot, shiftWindows))) {
                errors.add("Вікна зміни %d (група %s) не містять жодного слоту розкладу."
                        .formatted(group.shift(), group.code()));
            }
        }
        return groups;
    }

    private static Set<UUID> validateRooms(ProblemSnapshot snapshot, List<String> errors) {
        Set<UUID> rooms = new HashSet<>();
        for (ProblemSnapshot.Room room : snapshot.rooms()) {
            if (!rooms.add(room.id())) {
                errors.add("Аудиторія %s повторюється у знімку.".formatted(room.name()));
            }
            if (room.maxParallel() < 1) {
                errors.add("Аудиторія %s: кількість паралельних занять має бути не менше 1.".formatted(room.name()));
            }
            if (room.capacity() < 0) {
                errors.add("Аудиторія %s має від'ємну місткість.".formatted(room.name()));
            }
        }
        return rooms;
    }

    private static Set<UUID> validateTeachers(ProblemSnapshot snapshot, List<String> errors) {
        Set<UUID> teachers = new HashSet<>();
        for (ProblemSnapshot.Teacher teacher : snapshot.teachers()) {
            if (!teachers.add(teacher.id())) {
                errors.add("Викладач %s повторюється у знімку.".formatted(teacher.name()));
            }
            List<ProblemSnapshot.Timeslot> slots = new ArrayList<>(teacher.unavailable());
            slots.addAll(teacher.unwanted());
            for (ProblemSnapshot.Timeslot slot : slots) {
                if (!isValidSlot(slot.weekday(), slot.pair())) {
                    errors.add("Побажання викладача %s містять некоректний слот «%s»."
                            .formatted(teacher.name(), slotLabel(slot.weekday(), slot.pair())));
                }
            }
        }
        return teachers;
    }

    private static void validateUnits(ProblemSnapshot snapshot, Set<Timeslot> timeslots,
                                      Map<UUID, ProblemSnapshot.Group> groups, Set<UUID> rooms, Set<UUID> teachers,
                                      List<String> errors) {
        if (snapshot.units().isEmpty()) {
            errors.add("Немає жодного заняття для розміщення.");
            return;
        }
        Set<String> unitIds = new HashSet<>();
        boolean needsRooms = false;
        for (ProblemSnapshot.Unit unit : snapshot.units()) {
            String label = "Заняття " + unit.unitId();
            if (!unitIds.add(unit.unitId())) {
                errors.add(label + " повторюється у знімку.");
            }
            ProblemSnapshot.Group group = groups.get(unit.groupId());
            if (group == null) {
                errors.add(label + " посилається на невідому групу " + unit.groupId() + ".");
            } else if (unit.subgroup() != 0 && !group.subgroups().contains(unit.subgroup())) {
                errors.add("%s: група %s не має підгрупи %d.".formatted(label, group.code(), unit.subgroup()));
            }
            if (unit.teacherId() != null && !teachers.contains(unit.teacherId())) {
                errors.add(label + " посилається на невідомого викладача " + unit.teacherId() + ".");
            }
            for (UUID roomId : new UUID[] {unit.preferredRoomId(), unit.homeRoomId()}) {
                if (roomId != null && !rooms.contains(roomId)) {
                    errors.add(label + " посилається на невідому аудиторію " + roomId + ".");
                }
            }
            ProblemSnapshot.PinnedSlot pinned = unit.pinned();
            if (pinned == null) {
                needsRooms = true;
            } else {
                validatePin(label, pinned, timeslots, rooms, errors);
            }
        }
        if (needsRooms && rooms.isEmpty()) {
            errors.add("У знімку немає жодної аудиторії — заняттям нікуди стати.");
        }
    }

    private static void validatePin(String label, ProblemSnapshot.PinnedSlot pinned, Set<Timeslot> timeslots,
                                    Set<UUID> rooms, List<String> errors) {
        if (!isValidSlot(pinned.weekday(), pinned.pair())
                || !timeslots.contains(new Timeslot(pinned.weekday(), pinned.pair()))) {
            errors.add("%s закріплене у слоті «%s», якого немає в розкладі дзвінків."
                    .formatted(label, slotLabel(pinned.weekday(), pinned.pair())));
        }
        if (pinned.parity() == null) {
            errors.add(label + " закріплене без парності тижня.");
        }
        if (pinned.roomId() != null && !rooms.contains(pinned.roomId())) {
            errors.add(label + " закріплене в невідомій аудиторії " + pinned.roomId() + ".");
        }
    }

    private static boolean inAnyWindow(Timeslot slot, List<ProblemSnapshot.ShiftWindow> windows) {
        return windows.stream().anyMatch(window -> window.weekday() == slot.weekday()
                && slot.pair() >= window.firstPair() && slot.pair() <= window.lastPair());
    }

    private static boolean isValidSlot(int weekday, int pair) {
        return weekday >= 1 && weekday <= Timeslot.MAX_WEEKDAY && pair >= 1 && pair <= Timeslot.MAX_PAIR;
    }

    private static String slotLabel(int weekday, int pair) {
        return "день " + weekday + ", пара " + pair;
    }
}
