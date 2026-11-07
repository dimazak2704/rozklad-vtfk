package ua.vtc.rozklad.importer.timetable;

import java.util.Map;
import java.util.Optional;
import ua.vtc.rozklad.importer.text.TextNormalizer;

/**
 * Abbreviations whose full names are certain (DOMAIN §10). Everything else keeps the abbreviation
 * as its name and is marked {@code needsReview} — names are never invented.
 */
public final class KnownDisciplines {

    private static final String NDEKSM = "Надійність, діагностика та експлуатація комп'ютерних систем і мереж";

    // ключ — TextNormalizer.abbreviationKey(скорочення)
    private static final Map<String, String> FULL_NAMES = Map.ofEntries(
            Map.entry("матем", "Математика"),
            Map.entry("укрмова", "Українська мова"),
            Map.entry("укрліт", "Українська література"),
            Map.entry("зарліт", "Зарубіжна література"),
            Map.entry("інмова", "Іноземна мова"),
            Map.entry("всіст", "Всесвітня історія"),
            Map.entry("істукр", "Історія України"),
            Map.entry("геогр", "Географія"),
            Map.entry("біе", "Біологія і екологія"),
            Map.entry("фіа", "Фізика і астрономія"),
            Map.entry("хімія", "Хімія"),
            Map.entry("інф", "Інформатика"),
            Map.entry("фк", "Фізична культура"),
            Map.entry("фв", "Фізичне виховання"),
            Map.entry("го", "Громадянська освіта"),
            Map.entry("культ", "Культурологія"),
            Map.entry("зу", "Захист України"),
            Map.entry("бж", "Безпека життєдіяльності"),
            Map.entry("встспц", "Вступ до спеціальності"),
            Map.entry("стп", "Сучасні технології проєктування"),
            Map.entry("ндексм", NDEKSM),
            Map.entry("ндкс", NDEKSM),
            // у розкладі 2025/26 саме так («НДЕКС», веде Непийвода) — та сама дисципліна
            Map.entry("ндекс", NDEKSM),
            Map.entry("опцз", "Охорона праці та цивільний захист"),
            Map.entry("тпмр", "Технології проєктування мережевих ресурсів"),
            Map.entry("тое", "Теоретичні основи електротехніки"),
            Map.entry("пдр", "Правила дорожнього руху"),
            Map.entry("ікт", "Інформаційно-комунікаційні технології"));

    private KnownDisciplines() {
    }

    /** Full name for an abbreviation as written (any dots/case), if it is certain. */
    public static Optional<String> fullName(String abbreviation) {
        return Optional.ofNullable(FULL_NAMES.get(TextNormalizer.abbreviationKey(abbreviation)));
    }

    /** Abbreviation key → full name, for matching full names against the catalog. */
    public static Map<String, String> all() {
        return FULL_NAMES;
    }
}
