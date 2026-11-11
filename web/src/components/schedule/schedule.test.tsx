import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { BELLS_MON_THU, DEMO_WEEK } from '@/pages/dev/demo-data';
import { renderWithProviders } from '@/test/render';
import { DayList } from './DayList';
import { LessonCell } from './LessonCell';
import { NowIndicator } from './NowIndicator';
import { WeekGrid } from './WeekGrid';

const monday = DEMO_WEEK.days[0]!;
const substituted = monday.lessons[1]!;

describe('LessonCell', () => {
  it('показує заміну поверх розкладу: новий предмет, бейдж і закреслений оригінал', () => {
    renderWithProviders(<LessonCell lesson={substituted} />);
    const cell = screen.getByRole('button', { name: /2 пара/ });
    expect(cell).toHaveAccessibleName(/Заміна, замість: СТП, Кренцін\. Причина: Зв\.зам\./);
    expect(within(cell).getByText('НДЕКСМ')).toBeInTheDocument();
    expect(within(cell).getByText('Заміна')).toBeInTheDocument();
    expect(within(cell).getByText('СТП · Кренцін').tagName).toBe('S');
  });

  it('відкриває повні деталі з клавіатури', async () => {
    const user = userEvent.setup();
    renderWithProviders(<LessonCell lesson={substituted} date={monday.date} dayParity="NUMERATOR" />);
    await user.tab();
    await user.keyboard('{Enter}');
    const details = await screen.findByRole('dialog', { name: /Деталі: НДЕКСМ, 2 пара/ });
    expect(within(details).getByText('Надійність, діагностика та експлуатація компʼютерних систем і мереж')).toBeInTheDocument();
    expect(within(details).getByText('Непийвода М.В.')).toBeInTheDocument();
    // код розпорядження в публічній картці розгорнуто словами
    expect(within(details).getByText('взаємна заміна')).toBeInTheDocument();
    expect(within(details).getByText(/чисельник/)).toBeInTheDocument();
  });

  it('для викладача показує групу замість викладача', () => {
    renderWithProviders(<LessonCell lesson={monday.lessons[0]!} perspective="teacher" />);
    expect(screen.getByText('4КІ1')).toBeInTheDocument();
  });
});

describe('WeekGrid', () => {
  it('будує таблицю: дні — стовпці, пари — рядки з часом, сьогодні позначено', () => {
    renderWithProviders(<WeekGrid schedule={DEMO_WEEK} today="2026-11-09" at={10 * 60} />);
    const table = screen.getByRole('table', { name: /Розклад 4КІ1 на 9–13/ });
    const columnHeaders = within(table).getAllByRole('columnheader');
    expect(columnHeaders).toHaveLength(6);
    expect(columnHeaders[1]).toHaveAttribute('aria-current', 'date');
    const rowHeaders = within(table).getAllByRole('rowheader');
    // номер пари, початок і кінець — кожне своїм рядком (кінець для читача екрана — «до 09:30»)
    expect(rowHeaders.map((h) => h.textContent?.slice(0, 14))).toEqual(['108:30до 09:30', '209:40до 10:40', '311:00до 12:00', '412:10до 13:10']);
  });

  it('лінія «зараз» має пігулку з часом у колонці часу, а не поверх заняття', () => {
    renderWithProviders(<WeekGrid schedule={DEMO_WEEK} today="2026-11-09" at={10 * 60} />);
    const table = screen.getByRole('table', { name: /Розклад 4КІ1/ });
    const secondRow = within(table).getAllByRole('rowheader')[1]!;
    expect(secondRow).toHaveTextContent('10:00');
  });

  it('пʼятнична пара з іншим дзвінком показує свій час', () => {
    renderWithProviders(<WeekGrid schedule={DEMO_WEEK} today="2026-11-09" at={10 * 60} />);
    // у понеділок–четвер 1–3 пари мають той самий час, що й у пʼятницю, тож окремого часу немає
    expect(screen.queryByText('12:20–13:20')).not.toBeInTheDocument();
  });
});

describe('DayList і NowIndicator', () => {
  it('позначає поточну пару і показує зворотний відлік', () => {
    renderWithProviders(
      <>
        <NowIndicator bells={BELLS_MON_THU} lessonPairs={[1, 2, 3, 4]} at={10 * 60 + 17} />
        <DayList day={monday} bells={BELLS_MON_THU} today={monday.date} at={10 * 60 + 17} />
      </>,
    );
    expect(screen.getByText('23')).toBeInTheDocument();
    expect(screen.getByText('хв до дзвінка')).toBeInTheDocument();
    const current = screen.getAllByRole('listitem').find((item) => item.getAttribute('aria-current') === 'time');
    expect(current).toBeDefined();
    // у денному списку предмет названо повністю, а не скороченням
    expect(within(current!).getByText('Надійність, діагностика та експлуатація компʼютерних систем і мереж')).toBeInTheDocument();
  });

  it('доріжка дня підписує щонайбільше дві пари — поточну й наступну, кожну у своїй колонці', () => {
    renderWithProviders(<NowIndicator bells={BELLS_MON_THU} lessonPairs={[1, 2, 3, 4]} at={10 * 60 + 17} />);
    const track = screen.getByRole('img', { name: /Стан навчального дня/ });
    const labels = [...track.querySelectorAll('.row-start-3')].map((node) => node.textContent);
    expect(labels).toEqual(['09:40–10:40', '11:00']);
    // підписи — у потоці сітки, а не абсолютно за часом (саме це давало «08:3Ø9:40»)
    track.querySelectorAll('.row-start-3').forEach((node) => expect(node.className).not.toMatch(/absolute/));
  });

  it('пояснює, коли пари закінчилися', () => {
    renderWithProviders(<NowIndicator bells={BELLS_MON_THU} lessonPairs={[1, 2]} at={15 * 60} />);
    expect(screen.getByText('На сьогодні все')).toBeInTheDocument();
    expect(screen.getByText(/Остання пара закінчилася о 10:40/)).toBeInTheDocument();
  });
});
