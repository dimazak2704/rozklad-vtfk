package ua.vtc.rozklad.core.load.internal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ua.vtc.rozklad.core.calendar.CalendarQueries;
import ua.vtc.rozklad.core.load.LoadItemRepository;

/** The start-up recount never stops core from starting: a semester that fails is skipped, the others go on. */
class LoadPlanEventsTest {

    private final LoadPlanner planner = mock(LoadPlanner.class);
    private final LoadItemRepository items = mock(LoadItemRepository.class);
    private final LoadPlanEvents events = new LoadPlanEvents(planner, mock(CalendarQueries.class), items,
            new TransactionTemplate(mock(PlatformTransactionManager.class)));

    @Test
    void aSemesterThatFailsIsSkippedAndTheOthersAreRecounted() {
        UUID broken = UUID.randomUUID();
        UUID next = UUID.randomUUID();
        when(items.findSemesterIds()).thenReturn(List.of(broken, next));
        when(planner.recount(broken, null, null, null)).thenThrow(new IllegalStateException("bad calendar data"));

        assertThatCode(events::recountOnStart).doesNotThrowAnyException();

        verify(planner).recount(next, null, null, null);
    }

    @Test
    void unreadableSemestersDoNotStopTheStart() {
        when(items.findSemesterIds()).thenThrow(new IllegalStateException("database is not there"));

        assertThatCode(events::recountOnStart).doesNotThrowAnyException();

        verifyNoInteractions(planner);
    }
}
