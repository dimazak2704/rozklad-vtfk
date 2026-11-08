package ua.vtc.rozklad.core.timetable.internal.version;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.core.timetable.TemplateLesson;

/** The conflict engine sees an online template lesson without its room (it does not occupy it). */
class VersionAnalysisSlotLessonTest {

    @Test
    void onlineLessonIsCheckedWithoutItsRoom() {
        UUID room = UUID.randomUUID();
        TemplateLesson lesson = new TemplateLesson(UUID.randomUUID(), 1, 2, Parity.BOTH, UUID.randomUUID(), 0,
                UUID.randomUUID());
        lesson.setRoomId(room);

        assertThat(VersionAnalysis.slotLesson(lesson, null).roomId()).isEqualTo(room);

        lesson.setOnline(true);
        assertThat(VersionAnalysis.slotLesson(lesson, null).roomId()).isNull();
    }
}
