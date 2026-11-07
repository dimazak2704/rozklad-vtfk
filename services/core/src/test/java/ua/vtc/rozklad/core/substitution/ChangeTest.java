package ua.vtc.rozklad.core.substitution;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ua.vtc.rozklad.contracts.ChangeKind;

/** Reason printed in the order's «Причина заміни» column (DOMAIN §7). */
class ChangeTest {

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
            "REPLACE_TEACHER, ABSENT, null, Відп.",
            "REPLACE_DISCIPLINE, MUTUAL, null, Зв.зам.",
            "CANCEL, BUSINESS_TRIP, null, Відр.",
            "SWAP, OTHER, Перенесено, Перенесено",
            "CANCEL, OTHER, '  ', null",
            "DISCIPLINE_FINISHED, FINISHED, null, Вичит.",
            "ADDITIONAL, ABSENT, null, null",
            "BLOCK, OTHER, Навчальні збори, null",
            "ROOM_CHANGE, OTHER, Ремонт, null"})
    void printedReason(ChangeKind kind, ReasonCode code, String text, String printed) {
        Change change = new Change(kind, LocalDate.of(2026, 10, 19), 2, 2, UUID.randomUUID(), 0, "admin");
        change.setReason(code, text);

        assertThat(change.printedReason()).isEqualTo(printed);
    }
}
