package ua.vtc.rozklad.core.calendar.internal;

import static ua.vtc.rozklad.core.shared.persistence.EntityGuards.checkVersion;
import static ua.vtc.rozklad.core.shared.persistence.EntityGuards.deleteOrConflict;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.core.calendar.BellProfile;
import ua.vtc.rozklad.core.calendar.BellProfileRepository;
import ua.vtc.rozklad.core.calendar.internal.CalendarDtos.BellProfileDto;
import ua.vtc.rozklad.core.calendar.internal.CalendarDtos.BellProfileRequest;
import ua.vtc.rozklad.core.calendar.internal.CalendarDtos.BellSlotDto;
import ua.vtc.rozklad.core.calendar.internal.CalendarDtos.BellSlotRequest;
import ua.vtc.rozklad.core.settings.SettingsService;
import ua.vtc.rozklad.core.shared.error.ConflictException;
import ua.vtc.rozklad.core.shared.error.FieldErrors;
import ua.vtc.rozklad.core.shared.error.NotFoundException;

/**
 * Профілі дзвінків with their slots («2 зміни, 60 хв»). Slots of a weekday must go in pair order
 * without overlapping; the profile set as default in settings or used by a semester cannot be
 * deleted.
 */
@Service
@Transactional(readOnly = true)
public class BellProfileService {

    private static final String[] WEEKDAYS = {"понеділок", "вівторок", "середу", "четвер", "п'ятницю"};

    private final BellProfileRepository profiles;
    private final SettingsService settings;

    public BellProfileService(BellProfileRepository profiles, SettingsService settings) {
        this.profiles = profiles;
        this.settings = settings;
    }

    public List<BellProfileDto> list() {
        Optional<UUID> defaultId = settings.activeBellProfileId();
        return profiles.findAllByOrderByName().stream().map(profile -> toDto(profile, defaultId)).toList();
    }

    public BellProfileDto get(UUID id) {
        return toDto(find(id), settings.activeBellProfileId());
    }

    @Transactional
    public BellProfileDto create(BellProfileRequest request) {
        String name = validName(request.name(), null);
        validateSlots(request.slots());
        BellProfile profile = new BellProfile(name);
        profile.setActive(request.active() == null || request.active());
        request.slots().forEach(slot -> profile.addSlot(slot.weekday(), slot.pair(), slot.startsAt(), slot.endsAt()));
        profiles.save(profile);
        return toDto(profile, settings.activeBellProfileId());
    }

    @Transactional
    public BellProfileDto update(UUID id, BellProfileRequest request) {
        BellProfile profile = find(id);
        checkVersion(profile, request.version());
        profile.setName(validName(request.name(), id));
        validateSlots(request.slots());
        Optional<UUID> defaultId = settings.activeBellProfileId();
        boolean active = request.active() == null || request.active();
        if (!active && defaultId.filter(id::equals).isPresent()) {
            throw new ConflictException("Типовий профіль дзвінків не можна вимкнути: спершу виберіть інший у налаштуваннях.");
        }
        profile.setActive(active);
        // старі слоти видаляє orphanRemoval; унікальний ключ слота відкладений до коміту
        profile.clearSlots();
        request.slots().forEach(slot -> profile.addSlot(slot.weekday(), slot.pair(), slot.startsAt(), slot.endsAt()));
        profiles.flush();
        return toDto(profile, defaultId);
    }

    @Transactional
    public void delete(UUID id) {
        BellProfile profile = find(id);
        if (settings.activeBellProfileId().filter(id::equals).isPresent()) {
            throw new ConflictException("Профіль «%s» — типовий у налаштуваннях: спершу виберіть інший."
                    .formatted(profile.getName()));
        }
        deleteOrConflict(profiles, profile, () -> "Профіль «%s» використовує семестр: спершу змініть дзвінки семестру."
                .formatted(profile.getName()));
    }

    BellProfile find(UUID id) {
        return profiles.findWithSlotsById(id).orElseThrow(() -> NotFoundException.of("Профіль дзвінків", id));
    }

    static BellProfileDto toDto(BellProfile profile, Optional<UUID> defaultId) {
        List<BellSlotDto> slots = profile.getSlots().stream()
                .sorted(Comparator.comparingInt((ua.vtc.rozklad.core.calendar.BellSlot slot) -> slot.getWeekday())
                        .thenComparingInt(ua.vtc.rozklad.core.calendar.BellSlot::getPairNumber))
                .map(slot -> new BellSlotDto(slot.getWeekday(), slot.getPairNumber(), slot.getStartsAt(), slot.getEndsAt()))
                .toList();
        return new BellProfileDto(profile.getId(), profile.getName(), profile.isActive(),
                defaultId.filter(profile.getId()::equals).isPresent(), slots, profile.getVersion());
    }

    private String validName(String raw, UUID ownId) {
        String name = raw.strip();
        profiles.findByNameIgnoreCase(name)
                .filter(existing -> !existing.getId().equals(ownId))
                .ifPresent(existing -> {
                    throw new ConflictException("Профіль дзвінків «%s» уже існує.".formatted(existing.getName()));
                });
        return name;
    }

    /** Unique (weekday, pair); end after start; within a weekday a later pair starts after the previous one ends. */
    static void validateSlots(List<BellSlotRequest> slots) {
        FieldErrors errors = new FieldErrors();
        Map<String, Integer> seen = new HashMap<>();
        for (int i = 0; i < slots.size(); i++) {
            BellSlotRequest slot = slots.get(i);
            if (!slot.endsAt().isAfter(slot.startsAt())) {
                errors.add("slots[%d].endsAt".formatted(i), "Пара має закінчуватися пізніше, ніж починається.");
            }
            if (seen.putIfAbsent(slot.weekday() + ":" + slot.pair(), i) != null) {
                errors.add("slots[%d].pair".formatted(i), "Пара %d у %s повторюється."
                        .formatted(slot.pair(), WEEKDAYS[slot.weekday() - 1]));
            }
        }
        for (int weekday = 1; weekday <= 5; weekday++) {
            int day = weekday;
            List<Integer> order = new java.util.ArrayList<>();
            for (int i = 0; i < slots.size(); i++) {
                if (slots.get(i).weekday() == day) {
                    order.add(i);
                }
            }
            order.sort(Comparator.comparingInt(index -> slots.get(index).pair()));
            for (int k = 1; k < order.size(); k++) {
                BellSlotRequest previous = slots.get(order.get(k - 1));
                BellSlotRequest current = slots.get(order.get(k));
                if (current.startsAt().isBefore(previous.endsAt())) {
                    errors.add("slots[%d].startsAt".formatted(order.get(k)),
                            "Пара %d у %s починається раніше, ніж закінчується пара %d."
                                    .formatted(current.pair(), WEEKDAYS[day - 1], previous.pair()));
                }
            }
        }
        errors.throwIfAny();
    }
}
