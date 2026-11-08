package ua.vtc.rozklad.core.substitution.internal.change;

import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.ErrorResponseException;
import ua.vtc.rozklad.core.shared.error.BusinessRuleException;
import ua.vtc.rozklad.core.substitution.internal.options.LessonOptions;
import ua.vtc.rozklad.core.substitution.internal.options.RankedOption;

/**
 * One INFO line per decision the dispatcher makes about a lesson (no migration, DOMAIN §7):
 * {@code substitution.recommendation lesson=… change=… chosenKind=… chosenTeacher=… shownRank=… band=…
 * options=…}. The rank is the place of the chosen option in the ranking as it was just before the
 * change was created ({@code -} when it was not offered, e.g. a manual REPLACE_TEACHER). Only ids,
 * no names. It feeds the offline evaluation of the ranking.
 */
@Component
class RecommendationLog {

    private static final Logger LOG = LoggerFactory.getLogger("substitution.recommendation");

    private final LessonOptions options;

    RecommendationLog(LessonOptions options) {
        this.options = options;
    }

    /** What the ranking showed for the request's lesson, read before the change exists. */
    Shown before(CreateChangeRequest request) {
        if (request.originalLessonId() == null || request.kind() == null) {
            return Shown.NONE;
        }
        List<RankedOption> ranked;
        try {
            ranked = options.ranked(request.originalLessonId());
        } catch (BusinessRuleException | ErrorResponseException error) {
            // заняття вже не стоїть у розкладі — створення однаково відмовить, рангу немає
            LOG.debug("No ranking for lesson {}: {}", request.originalLessonId(), error.getMessage());
            return Shown.NONE;
        }
        for (int index = 0; index < ranked.size(); index++) {
            RankedOption option = ranked.get(index);
            if (option.kind() == request.kind() && sameChoice(option, request)) {
                return new Shown(index + 1, option.band().name(), ranked.size());
            }
        }
        return new Shown(null, null, ranked.size());
    }

    void after(Shown shown, CreateChangeRequest request, ChangeDto created) {
        LOG.info("substitution.recommendation lesson={} change={} chosenKind={} chosenTeacher={} shownRank={} band={} "
                        + "options={}", Objects.toString(request.originalLessonId(), "-"), created.id(), request.kind(),
                created.replacement() == null || created.replacement().teacher() == null ? "-"
                        : created.replacement().teacher().id(), shown.rank() == null ? "-" : shown.rank(),
                shown.band() == null ? "-" : shown.band(), shown.options() == null ? "-" : shown.options());
    }

    private static boolean sameChoice(RankedOption option, CreateChangeRequest request) {
        return switch (request.kind()) {
            case SWAP, MERGE_SUBGROUPS -> Objects.equals(option.swapLessonId(), request.swapLessonId());
            case CANCEL -> true;
            default -> Objects.equals(option.teacherId(), request.newTeacherId());
        };
    }

    /** Place (1-based) and band of the chosen option, and how many options there were. */
    record Shown(Integer rank, String band, Integer options) {

        static final Shown NONE = new Shown(null, null, null);
    }
}
