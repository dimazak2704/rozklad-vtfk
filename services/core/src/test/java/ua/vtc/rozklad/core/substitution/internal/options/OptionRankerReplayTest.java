package ua.vtc.rozklad.core.substitution.internal.options;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import ua.vtc.rozklad.contracts.ChangeKind;
import ua.vtc.rozklad.core.shared.text.UkrainianText;

/**
 * Offline evaluation of the ranking on the real decisions of order №35 (19.10.2026), replayed from
 * the state before any decision of the day ({@code scripts/eval/export_replay_cases.py}). Prints
 * hit@1 / hit@3 / mean rank with Wilson 95 % intervals, an alphabetical and a seeded random baseline
 * and an ablation table. N is tiny (13 decisions, 9 «Зв.зам.»): the numbers describe, they do not
 * tune; the weights stay as documented in DOMAIN §7. Only the shape of the data is asserted.
 *
 * <p>Run: {@code mvn -pl services/core -am test -Dtest=OptionRankerReplayTest -Dtest.excludedGroups=
 * -Dsurefire.failIfNoSpecifiedTests=false}; the report is also written to
 * {@code target/replay-order35.txt}.
 */
@Tag("eval")
class OptionRankerReplayTest {

    private static final int RANDOM_DRAWS = 2000;

    record Real(ChangeKind kind, String reasonCode, UUID teacherId, UUID disciplineId, String teacher,
                String discipline) {
    }

    record Case(String group, int pair, int subgroup, String lesson, Real real, RankingInput input) {

        String label() {
            return group + " " + pair + " пара" + (subgroup == 0 ? "" : " (п." + subgroup + ")") + ", " + lesson;
        }

        /** «Зв.зам.»: another discipline of the group by its own teacher. */
        boolean mutualCover() {
            return real.kind() == ChangeKind.REPLACE_DISCIPLINE;
        }
    }

    record Export(String source, String exportedOn, Map<UUID, String> teachers, List<Case> cases) {
    }

    private static Export export() throws IOException {
        try (InputStream json = OptionRankerReplayTest.class.getResourceAsStream("/replay/order35.json")) {
            return JsonMapper.builder()
                    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .build()
                    .readValue(json, Export.class);
        }
    }

    /** 1-based place of the real choice (kind and teacher), {@code null} when it is not in the list. */
    static Integer rankOf(Case replay, List<RankedOption> options) {
        for (int index = 0; index < options.size(); index++) {
            RankedOption option = options.get(index);
            if (option.kind() == replay.real().kind()
                    && (replay.real().teacherId() == null || replay.real().teacherId().equals(option.teacherId()))) {
                return index + 1;
            }
        }
        return null;
    }

    @Test
    void replayOfOrder35() throws IOException {
        Export export = export();
        List<Case> cases = export.cases();
        assertThat(cases).hasSize(13);
        assertThat(cases).filteredOn(Case::mutualCover).hasSize(9);

        StringBuilder report = new StringBuilder();
        report.append("Replay: ").append(export.source()).append(" (exported ").append(export.exportedOn())
                .append(")\n\n");
        report.append("Per case (v2, all factors):\n");
        for (Case replay : cases) {
            List<RankedOption> options = OptionRanker.rank(replay.input());
            assertThat(options).as("never recommended").extracting(RankedOption::kind)
                    .doesNotContain(ChangeKind.REPLACE_TEACHER, ChangeKind.ADDITIONAL);
            assertThat(OptionRanker.rank(replay.input())).as("deterministic").isEqualTo(options);
            Integer rank = rankOf(replay, options);
            report.append(String.format(Locale.ROOT, "  %-40s real %-18s %-24s rank %-4s of %2d | top: %s%n",
                    replay.label(), replay.real().kind(), Objects.toString(replay.real().discipline(), "") + " "
                            + Objects.toString(replay.real().teacher(), ""), rank == null ? "miss" : rank,
                    options.size(), options.stream().limit(3).map(RankedOption::title).toList()));
        }

        report.append("\nMetrics (Wilson 95 % intervals):\n");
        report.append(metricsLine("v2", cases, replay -> OptionRanker.rank(replay.input())));
        report.append(metricsLine("alphabetical (same options)", cases, replay -> alphabetical(replay)));
        report.append(randomLine(cases));

        report.append("\nAblation (one factor switched off):\n");
        for (RankingFactor factor : RankingFactor.values()) {
            report.append(metricsLine("without " + factor, cases,
                    replay -> OptionRanker.rank(replay.input(), EnumSet.of(factor))));
        }
        report.append(metricsLine("without all cost factors", cases,
                replay -> OptionRanker.rank(replay.input(), EnumSet.allOf(RankingFactor.class))));

        String text = report.toString();
        System.out.println(text);
        Path target = Path.of("target");
        if (Files.isDirectory(target)) {
            Files.writeString(target.resolve("replay-order35.txt"), text, StandardCharsets.UTF_8);
        }
    }

    private static List<RankedOption> alphabetical(Case replay) {
        Comparator<String> collator = UkrainianText.collator();
        return OptionRanker.rank(replay.input()).stream()
                .sorted(Comparator.comparing(RankedOption::title, collator))
                .toList();
    }

    /** RD hit@1, hit@3, mean rank over the 9 «Зв.зам.» decisions; kind+teacher in top 3 over all 13. */
    private static String metricsLine(String name, List<Case> cases, Function<Case, List<RankedOption>> ranking) {
        int hit1 = 0;
        int hit3 = 0;
        int rankSum = 0;
        int top3 = 0;
        int mutual = 0;
        for (Case replay : cases) {
            List<RankedOption> options = ranking.apply(replay);
            Integer rank = rankOf(replay, options);
            if (rank != null && rank <= 3) {
                top3++;
            }
            if (replay.mutualCover()) {
                mutual++;
                int effective = rank == null ? options.size() + 1 : rank;
                rankSum += effective;
                hit1 += effective == 1 ? 1 : 0;
                hit3 += effective <= 3 ? 1 : 0;
            }
        }
        return String.format(Locale.ROOT, "  %-34s RD hit@1 %d/%d %s  hit@3 %d/%d %s  mean rank %.2f | all top-3 %d/%d %s%n",
                name, hit1, mutual, wilson(hit1, mutual), hit3, mutual, wilson(hit3, mutual),
                rankSum / (double) mutual, top3, cases.size(), wilson(top3, cases.size()));
    }

    /** Seeded random order of the same options, averaged over many draws. */
    private static String randomLine(List<Case> cases) {
        Random random = new Random(2026);
        double hit1 = 0;
        double hit3 = 0;
        double rankSum = 0;
        double top3 = 0;
        int mutual = (int) cases.stream().filter(Case::mutualCover).count();
        for (int draw = 0; draw < RANDOM_DRAWS; draw++) {
            for (Case replay : cases) {
                List<RankedOption> options = new ArrayList<>(OptionRanker.rank(replay.input()));
                Collections.shuffle(options, random);
                Integer rank = rankOf(replay, options);
                if (rank != null && rank <= 3) {
                    top3++;
                }
                if (replay.mutualCover()) {
                    int effective = rank == null ? options.size() + 1 : rank;
                    rankSum += effective;
                    hit1 += effective == 1 ? 1 : 0;
                    hit3 += effective <= 3 ? 1 : 0;
                }
            }
        }
        return String.format(Locale.ROOT, "  %-34s RD hit@1 %.2f/%d  hit@3 %.2f/%d  mean rank %.2f | all top-3 %.2f/%d "
                        + "(mean of %d seeded draws)%n", "random (same options)", hit1 / RANDOM_DRAWS, mutual,
                hit3 / RANDOM_DRAWS, mutual, rankSum / RANDOM_DRAWS / mutual, top3 / RANDOM_DRAWS, cases.size(),
                RANDOM_DRAWS);
    }

    /** Wilson score interval for k successes of n at 95 %. */
    static String wilson(int successes, int trials) {
        if (trials == 0) {
            return "[—]";
        }
        double z = 1.96;
        double p = successes / (double) trials;
        double denominator = 1 + z * z / trials;
        double centre = (p + z * z / (2 * trials)) / denominator;
        double half = z * Math.sqrt(p * (1 - p) / trials + z * z / (4.0 * trials * trials)) / denominator;
        return String.format(Locale.ROOT, "[%.2f–%.2f]", Math.max(0, centre - half), Math.min(1, centre + half));
    }

    @Test
    void wilsonIntervalOfSixOfNine() {
        assertThat(wilson(6, 9)).isEqualTo("[0.35–0.88]");
        assertThat(Set.of(wilson(0, 9))).containsExactly("[0.00–0.30]");
    }
}
