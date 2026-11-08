package ua.vtc.rozklad.core.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.contracts.RoomType;
import ua.vtc.rozklad.core.catalog.BuildingRepository;
import ua.vtc.rozklad.core.catalog.CycleCommissionRepository;
import ua.vtc.rozklad.core.catalog.DepartmentRepository;
import ua.vtc.rozklad.core.catalog.Room;
import ua.vtc.rozklad.core.catalog.RoomRepository;
import ua.vtc.rozklad.core.catalog.Specialty;
import ua.vtc.rozklad.core.catalog.SpecialtyRepository;
import ua.vtc.rozklad.core.catalog.StudentGroup;
import ua.vtc.rozklad.core.catalog.StudentGroupRepository;
import ua.vtc.rozklad.core.catalog.Teacher;
import ua.vtc.rozklad.core.catalog.TeacherAlias;
import ua.vtc.rozklad.core.catalog.TeacherAliasRepository;
import ua.vtc.rozklad.core.catalog.TeacherRepository;
import ua.vtc.rozklad.core.catalog.internal.PublicCatalogDtos.SearchResultDto;
import ua.vtc.rozklad.core.catalog.internal.PublicCatalogDtos.SearchResultType;
import ua.vtc.rozklad.core.settings.SettingsService;

/** Ranking of {@code /api/public/search}: exact &gt; prefix &gt; fuzzy, across groups, teachers and rooms. */
class PublicCatalogSearchTest {

    private final StudentGroupRepository groups = mock(StudentGroupRepository.class);
    private final SpecialtyRepository specialties = mock(SpecialtyRepository.class);
    private final TeacherRepository teachers = mock(TeacherRepository.class);
    private final TeacherAliasRepository aliases = mock(TeacherAliasRepository.class);
    private final RoomRepository rooms = mock(RoomRepository.class);
    private final SettingsService settings = mock(SettingsService.class);
    private final PublicCatalogService service = new PublicCatalogService(groups, specialties,
            mock(DepartmentRepository.class), teachers, aliases, mock(CycleCommissionRepository.class), rooms,
            mock(BuildingRepository.class), settings);

    private Teacher krentsin;

    @BeforeEach
    void catalog() {
        Specialty ki = new Specialty("КІ", "Комп’ютерна інженерія");
        given(specialties.findAll()).willReturn(List.of(ki));
        given(groups.findAllByActiveTrueOrderByCourseAscCodeAsc()).willReturn(List.of(
                new StudentGroup("1КІ1", 1, ki.getId()), new StudentGroup("4КІ1", 4, ki.getId()),
                new StudentGroup("4КІ2", 4, ki.getId())));
        krentsin = new Teacher("Кренцін", "Кренцін М.Д.", "Кренцін");
        given(teachers.findAllByActiveTrueOrderByShortName()).willReturn(List.of(
                new Teacher("Коваль", "Коваль", "Коваль"),
                new Teacher("Ковальчук", "Ковальчук О.", "Ковальчук"),
                new Teacher("Ковань", "Ковань І.", "Ковань"),
                krentsin,
                new Teacher("Гарболінська", "Гарболінська О.", "Гарболінська")));
        given(aliases.findAll()).willReturn(List.of());
        Room gym = new Room("с.з.", RoomType.GYM);
        gym.setMaxParallel(3);
        given(rooms.findAll()).willReturn(List.of(new Room("6", RoomType.COMPUTER_LAB),
                new Room("16", RoomType.CLASSROOM), gym));
    }

    @Test
    void publicRoomsTellHowManyGroupsTheyHostAtOnce() {
        assertThat(service.rooms()).extracting(PublicCatalogDtos.PublicRoomDto::name,
                        PublicCatalogDtos.PublicRoomDto::maxParallel)
                .containsExactly(tuple("6", 1), tuple("16", 1), tuple("с.з.", 3));
    }

    @Test
    void latinGroupCodeFindsTheGroupFirst() {
        List<SearchResultDto> results = service.search("4ki1", 10);

        assertThat(results.getFirst()).satisfies(first -> {
            assertThat(first.type()).isEqualTo(SearchResultType.GROUP);
            assertThat(first.label()).isEqualTo("4КІ1");
            assertThat(first.sublabel()).isEqualTo("4 курс · Комп’ютерна інженерія");
            assertThat(first.url()).isEqualTo("/g/4%D0%9A%D0%861");
        });
    }

    @Test
    void beginningOfASurnameFindsTheTeacher() {
        List<SearchResultDto> results = service.search("кренц", 10);

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.type()).isEqualTo(SearchResultType.TEACHER);
            assertThat(result.label()).isEqualTo("Кренцін М.Д.");
            assertThat(result.url()).isEqualTo("/t/" + krentsin.getId());
        });
    }

    @Test
    void roomNumberFindsThatRoomOnly() {
        assertThat(service.search("6", 10)).extracting(SearchResultDto::label).containsExactly("ауд. 6");
    }

    @Test
    void exactBeatsPrefixBeatsFuzzy() {
        assertThat(service.search("Коваль", 10)).extracting(SearchResultDto::label)
                .containsExactly("Коваль", "Ковальчук О.", "Ковань І.");
    }

    @Test
    void aliasesAreSearchedButTheMainNameIsShown() {
        Teacher garbolinska = teachers.findAllByActiveTrueOrderByShortName().getLast();
        given(aliases.findAll()).willReturn(List.of(new TeacherAlias(garbolinska.getId(), "Граболінська", "граболінська")));

        assertThat(service.search("Граболінська", 10)).extracting(SearchResultDto::label)
                .containsExactly("Гарболінська О.");
    }

    @Test
    void limitAndBlankQuery() {
        assertThat(service.search("к", 2)).hasSize(2);
        assertThat(service.search("   ", 10)).isEmpty();
        assertThat(service.search("zzzz", 10)).isEmpty();
    }

    @Test
    void fullNamesAreSearchableOnlyWhenTheSettingAllowsIt() {
        krentsin.setFirstName("Микола");
        krentsin.setMiddleName("Дмитрович");

        assertThat(service.search("Микола", 10)).isEmpty();

        given(settings.showTeacherFullNames()).willReturn(true);
        assertThat(service.search("Микола", 10)).singleElement()
                .satisfies(result -> assertThat(result.sublabel()).isEqualTo("Кренцін Микола Дмитрович"));
    }
}
