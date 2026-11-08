package ua.vtc.rozklad.core.timetable;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.core.timetable.TeacherRooms.RoomUse;

class TeacherRoomsTest {

    private static final UUID KUCHYNSKA = id("teacher-kuchynska");
    private static final UUID VITIUK = id("teacher-vitiuk");
    private static final UUID MATH = id("discipline-math");
    private static final UUID PHYSICS = id("discipline-physics");
    private static final UUID KI1 = id("group-2KI1");
    private static final UUID KI2 = id("group-2KI2");
    private static final UUID ROOM_303 = id("room-303");
    private static final UUID ROOM_22 = id("room-22");
    private static final UUID ROOM_406 = id("room-406");
    private static final UUID GYM = id("room-gym");

    @Test
    void usualRoomIsTheRoomWithMostWeekOccurrences() {
        TeacherRooms rooms = TeacherRooms.of(List.of(
                use(KUCHYNSKA, MATH, KI1, ROOM_303, "303", 4),
                use(KUCHYNSKA, MATH, KI2, ROOM_303, "303", 2),
                use(KUCHYNSKA, MATH, KI2, ROOM_22, "22", 3)));

        assertThat(rooms.forTeacher(KUCHYNSKA)).contains(ROOM_303);
        assertThat(rooms.forDiscipline(KUCHYNSKA, MATH)).contains(ROOM_303);
        assertThat(rooms.forGroup(KUCHYNSKA, MATH, KI2)).as("in 2КІ2 she teaches mostly in 22").contains(ROOM_22);
    }

    @Test
    void tieGoesToTheLowestRoomNumberNumberedRoomsFirst() {
        TeacherRooms rooms = TeacherRooms.of(List.of(
                use(VITIUK, MATH, KI1, ROOM_406, "406", 2),
                use(VITIUK, MATH, KI1, ROOM_22, "22", 2),
                use(VITIUK, MATH, KI1, GYM, "Спортзал", 2),
                use(KUCHYNSKA, MATH, KI1, GYM, "Спортзал", 2),
                use(KUCHYNSKA, MATH, KI1, ROOM_303, "303", 2)));

        assertThat(rooms.forTeacher(VITIUK)).as("22 < 406 numerically, not as text").contains(ROOM_22);
        assertThat(rooms.forTeacher(KUCHYNSKA)).contains(ROOM_303);
    }

    @Test
    void lookupsFallBackFromGroupToDisciplineToTeacher() {
        TeacherRooms rooms = TeacherRooms.of(List.of(
                use(VITIUK, MATH, KI1, ROOM_406, "406", 4),
                use(VITIUK, PHYSICS, KI1, ROOM_22, "22", 2)));

        assertThat(rooms.forGroup(VITIUK, PHYSICS, KI1)).contains(ROOM_22);
        assertThat(rooms.forGroup(VITIUK, PHYSICS, KI2)).as("no physics in 2КІ2: physics room").contains(ROOM_22);
        assertThat(rooms.forDiscipline(VITIUK, id("discipline-new"))).as("a new discipline: his room")
                .contains(ROOM_406);
    }

    @Test
    void teacherWithoutLessonsHasNoUsualRoom() {
        TeacherRooms rooms = TeacherRooms.of(List.of(use(VITIUK, MATH, KI1, ROOM_406, "406", 2)));

        assertThat(rooms.forTeacher(KUCHYNSKA)).isEmpty();
        assertThat(rooms.forDiscipline(KUCHYNSKA, MATH)).isEmpty();
        assertThat(rooms.forGroup(null, MATH, KI1)).isEmpty();
        assertThat(TeacherRooms.NONE.forDiscipline(VITIUK, MATH)).isEmpty();
        assertThat(TeacherRooms.of(List.of())).isSameAs(TeacherRooms.NONE);
    }

    private static RoomUse use(UUID teacher, UUID discipline, UUID group, UUID room, String name, int occurrences) {
        return new RoomUse(teacher, discipline, group, room, name, occurrences);
    }

    private static UUID id(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }
}
