package ua.vtc.rozklad.notification.substitution;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.contracts.ChangeStatus;
import ua.vtc.rozklad.contracts.ConfirmationDecision;
import ua.vtc.rozklad.contracts.SubstitutionRequestMode;
import ua.vtc.rozklad.contracts.events.SubstitutionConfirmationRequested;
import ua.vtc.rozklad.notification.support.Order35;

/** The local copy follows core's events forward only: a late event never moves it back (DOMAIN §7). */
class SubstitutionRequestTest {

    private static final Instant NOW = Instant.parse("2026-10-18T15:30:00Z");

    @Test
    void finalStatusesTakeNoLaterEvent() {
        for (ChangeStatus last : new ChangeStatus[] {ChangeStatus.APPLIED, ChangeStatus.CANCELLED}) {
            SubstitutionRequest request = SubstitutionRequest.from(request(), NOW);
            request.changeStatus(ChangeStatus.CONFIRMED, NOW);
            assertThat(request.changeStatus(last, NOW)).isTrue();

            for (ChangeStatus late : ChangeStatus.values()) {
                assertThat(request.changeStatus(late, NOW)).as("%s after %s", late, last).isFalse();
            }
            assertThat(request.getStatus()).isEqualTo(last);
            assertThat(request.isFinal()).isTrue();
        }
    }

    @Test
    void confirmedRequestIsOnlyPublishedOrCancelled() {
        SubstitutionRequest request = SubstitutionRequest.from(request(), NOW);
        assertThat(request.changeStatus(ChangeStatus.CONFIRMED, NOW)).isTrue();

        // a refusal of an earlier round, re-sent after the teacher was asked again and confirmed
        assertThat(request.changeStatus(ChangeStatus.DECLINED, NOW)).isFalse();
        assertThat(request.changeStatus(ChangeStatus.PENDING_CONFIRMATION, NOW)).isFalse();
        assertThat(request.getStatus()).isEqualTo(ChangeStatus.CONFIRMED);
        assertThat(request.changeStatus(ChangeStatus.APPLIED, NOW)).isTrue();
    }

    /** core asked again after the refusal and the teacher confirmed; the new request is still on its way. */
    @Test
    void declinedRequestTakesTheConfirmationOfTheNextRound() {
        SubstitutionRequest request = SubstitutionRequest.from(request(), NOW);
        assertThat(request.changeStatus(ChangeStatus.DECLINED, NOW)).isTrue();
        assertThat(request.changeStatus(ChangeStatus.CONFIRMED, NOW)).isTrue();
    }

    @Test
    void declinedNoticeIsNotConfirmedByItsOwnOlderEvent() {
        SubstitutionRequest notice = SubstitutionRequest.from(notice(), NOW);
        assertThat(notice.recordAnswer(ConfirmationDecision.DECLINE, ChangeStatus.DECLINED, NOW)).isTrue();

        assertThat(notice.changeStatus(ChangeStatus.CONFIRMED, NOW)).isFalse();
        assertThat(notice.changeStatus(ChangeStatus.APPLIED, NOW)).isFalse();
        assertThat(notice.recordAnswer(ConfirmationDecision.CONFIRM, ChangeStatus.CONFIRMED, NOW)).isFalse();
        assertThat(notice.getStatus()).isEqualTo(ChangeStatus.DECLINED);
        assertThat(notice.getTeacherAnswer()).isEqualTo(ConfirmationDecision.DECLINE);
        assertThat(notice.changeStatus(ChangeStatus.CANCELLED, NOW)).isTrue();
    }

    /** «Добре» given before the order: its event may come after APPLIED; the answer counts, the status stays. */
    @Test
    void okBeforeTheOrderIsRecordedOnAPublishedNotice() {
        SubstitutionRequest notice = SubstitutionRequest.from(notice(), NOW);
        notice.changeStatus(ChangeStatus.APPLIED, NOW);

        assertThat(notice.recordAnswer(ConfirmationDecision.CONFIRM, ChangeStatus.CONFIRMED, NOW)).isTrue();
        assertThat(notice.getStatus()).isEqualTo(ChangeStatus.APPLIED);
        assertThat(notice.getTeacherAnswer()).isEqualTo(ConfirmationDecision.CONFIRM);
        assertThat(notice.recordAnswer(ConfirmationDecision.CONFIRM, ChangeStatus.CONFIRMED, NOW)).isFalse();
    }

    @Test
    void cancelledNoticeTakesNoLateAnswer() {
        SubstitutionRequest notice = SubstitutionRequest.from(notice(), NOW);
        notice.changeStatus(ChangeStatus.CANCELLED, NOW);

        assertThat(notice.recordAnswer(ConfirmationDecision.CONFIRM, ChangeStatus.CONFIRMED, NOW)).isFalse();
        assertThat(notice.recordAnswer(ConfirmationDecision.DECLINE, ChangeStatus.DECLINED, NOW)).isFalse();
        assertThat(notice.getStatus()).isEqualTo(ChangeStatus.CANCELLED);
        assertThat(notice.getTeacherAnswer()).isNull();
    }

    private static SubstitutionConfirmationRequested request() {
        return event(Instant.parse("2026-10-18T17:00:00Z"), null);
    }

    private static SubstitutionConfirmationRequested notice() {
        return event(null, SubstitutionRequestMode.NOTIFY);
    }

    private static SubstitutionConfirmationRequested event(Instant deadline, SubstitutionRequestMode mode) {
        return new SubstitutionConfirmationRequested(UUID.randomUUID(), NOW, 1, Order35.CHANGE_4KI1,
                Order35.TEACHER_NEPYIVODA, Order35.DATE, 2, LocalTime.of(9, 40), LocalTime.of(10, 40), "4КІ1", 0,
                "Надійність, діагностика та експлуатація комп'ютерних систем і мереж", "Кренцін М.Д.", "6",
                deadline, mode, null, null, null);
    }
}
