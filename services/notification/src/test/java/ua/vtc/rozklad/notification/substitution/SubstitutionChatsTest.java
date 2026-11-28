package ua.vtc.rozklad.notification.substitution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.contracts.events.SubstitutionConfirmationRequested;
import ua.vtc.rozklad.notification.binding.ChatBinding;
import ua.vtc.rozklad.notification.binding.ChatBindings;
import ua.vtc.rozklad.notification.delivery.DeliveryPurpose;
import ua.vtc.rozklad.notification.delivery.OutboundDraft;
import ua.vtc.rozklad.notification.delivery.OutboundKind;
import ua.vtc.rozklad.notification.delivery.Outbox;
import ua.vtc.rozklad.notification.support.Order35;

/** Edits and retirement of the request messages a teacher already holds. */
class SubstitutionChatsTest {

    private static final Instant NOW = Instant.parse("2026-10-18T15:30:00Z");
    private static final long CHAT = 100L;
    private static final long OTHER_CHAT = 200L;
    private static final UUID OTHER_TEACHER = UUID.fromString("5b2c4d1e-0f3a-4b5c-8d9e-1a2b3c4d5e70");

    private final SubstitutionMessageRepository messages = mock(SubstitutionMessageRepository.class);
    private final ChatBindings bindings = mock(ChatBindings.class);
    private final Outbox outbox = mock(Outbox.class);
    private final SubstitutionChats chats = new SubstitutionChats(messages, bindings, outbox);
    private final List<OutboundDraft> queued = new ArrayList<>();
    private SubstitutionRequest request;
    private List<SubstitutionMessage> sent;

    @BeforeEach
    void setUp() {
        request = SubstitutionRequest.from(event(), NOW);
        when(outbox.enqueue(any())).thenAnswer(call -> {
            queued.add(call.getArgument(0));
            return List.of();
        });
        when(bindings.teacherBinding(CHAT)).thenReturn(Optional.of(
                ChatBinding.teacher(CHAT, request.getTeacherId(), "Непийвода", NOW)));
        when(bindings.teacherBinding(OTHER_CHAT)).thenReturn(Optional.of(
                ChatBinding.teacher(OTHER_CHAT, OTHER_TEACHER, "Кренцін", NOW)));
        sent = List.of(message(CHAT, 1L), message(CHAT, 2L), message(OTHER_CHAT, 3L));
        when(messages.findByChangeId(request.getChangeId())).thenReturn(sent);
    }

    @Test
    void editsOnlyTheCurrentAddresseesMessagesAndSkipsTheTappedOne() {
        chats.editMessages(request, "Підтверджено", null, CHAT, 1L, null);

        assertThat(queued).singleElement().satisfies(draft -> {
            assertThat(draft.kind()).isEqualTo(OutboundKind.EDIT_TEXT);
            assertThat(draft.purpose()).isEqualTo(DeliveryPurpose.SUBSTITUTION_STATUS);
            assertThat(draft.editMessageId()).isEqualTo(2L);
            assertThat(draft.correlationId()).isEqualTo(request.getChangeId());
            // the teacher's own answer is keyed by the state it shows, so a double tap edits once
            assertThat(draft.dedupKey()).isEqualTo("reply:" + request.getChangeId() + ":2:" + request.getStatus());
        });
    }

    @Test
    void editsOfAnEventAreKeyedByTheEvent() {
        UUID eventId = UUID.randomUUID();

        chats.editMessages(request, "Скасовано", null, null, null, eventId);

        assertThat(queued).extracting(OutboundDraft::editMessageId).containsExactly(1L, 2L);
        assertThat(queued).extracting(OutboundDraft::dedupKey)
                .containsExactly(eventId + ":" + CHAT + ":edit-1", eventId + ":" + CHAT + ":edit-2");
    }

    @Test
    void retiringEditsEveryStillBoundMessageAndForgetsThem() {
        UUID eventId = UUID.randomUUID();

        chats.retireMessages(request, eventId);

        assertThat(queued).extracting(OutboundDraft::editMessageId).containsExactly(1L, 2L, 3L);
        assertThat(queued).allSatisfy(draft -> assertThat(draft.keyboard()).isNull());
        verify(messages).deleteAll(sent);
    }

    @Test
    void hasMessagesReflectsWhatReachedTheTeacher() {
        assertThat(chats.hasMessages(request.getChangeId())).isTrue();
        assertThat(chats.hasMessages(UUID.randomUUID())).isFalse();
    }

    private SubstitutionMessage message(long chatId, long messageId) {
        return new SubstitutionMessage(request.getChangeId(), chatId, messageId, NOW);
    }

    private static SubstitutionConfirmationRequested event() {
        return new SubstitutionConfirmationRequested(UUID.randomUUID(), NOW, 1, Order35.CHANGE_4KI1,
                Order35.TEACHER_NEPYIVODA, Order35.DATE, 2, LocalTime.of(9, 40), LocalTime.of(10, 40), "4КІ1", 0,
                "Надійність, діагностика та експлуатація комп'ютерних систем і мереж", "Кренцін М.Д.", "6",
                Instant.parse("2026-10-18T17:00:00Z"), null, null, null, null);
    }
}
