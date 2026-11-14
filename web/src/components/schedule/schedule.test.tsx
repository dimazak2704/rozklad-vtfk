import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import type { DayDto, PublicTeacherDto } from '@/api/types';
import { apiPath } from '@/mocks/kit';
import { server } from '@/mocks/server';
import { BELLS_MON_THU, DEMO_WEEK } from '@/pages/dev/demo-data';
import { renderWithProviders } from '@/test/render';
import { DayList } from './DayList';
import { GridNamesProvider } from './GridNames';
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
    // у картці — «Прізвище Імʼя»; імені в довіднику немає (налаштування повних імен вимкнене) — з ініціалами
    expect(within(details).getByText(/^Непийвода\sМ\.\sВ\.$/)).toBeInTheDocument();
    // код розпорядження в публічній картці розгорнуто словами
    expect(within(details).getByText('взаємна заміна')).toBeInTheDocument();
    expect(within(details).getByText(/чисельник/)).toBeInTheDocument();
  });

  it('для викладача показує групу замість викладача', () => {
    renderWithProviders(<LessonCell lesson={monday.lessons[0]!} perspective="teacher" />);
    expect(screen.getByText('4КІ1')).toBeInTheDocument();
  });

  it('для викладача першим рядком іде код групи, а дисципліна — тихим підписом', () => {
    const lesson = monday.lessons[0]!;
    renderWithProviders(<LessonCell lesson={lesson} perspective="teacher" />);
    const cell = screen.getByRole('button');
    const code = within(cell).getByText('4КІ1');
    const discipline = within(cell).getByText(lesson.discipline.shortName);
    expect(code).toHaveClass('font-display', 'text-cell');
    expect(discipline).not.toHaveClass('text-cell');
    // код групи стоїть раніше за дисципліну
    expect(code.compareDocumentPosition(discipline) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('для аудиторії виділяє лише код групи, а викладач лишається звичайним підписом', () => {
    const lesson = monday.lessons[0]!;
    renderWithProviders(<LessonCell lesson={lesson} perspective="room" />);
    const code = screen.getByText('4КІ1');
    expect(code).toHaveClass('font-display', 'font-semibold');
    expect(code.parentElement).toHaveTextContent(`4КІ1 · ${lesson.teacher!.displayName}`);
  });

  it('для групи викладач — тихий підпис без шрифту цифр', () => {
    const lesson = monday.lessons[0]!;
    renderWithProviders(<LessonCell lesson={lesson} perspective="group" />);
    expect(screen.getByText(lesson.teacher!.displayName)).not.toHaveClass('font-display');
  });
});

describe('імена викладачів', () => {
  it('у картці — «Прізвище Імʼя», коли довідник віддає імʼя (налаштування повних імен увімкнене)', async () => {
    const teacher = substituted.teacher!;
    const named: PublicTeacherDto = {
      id: teacher.id,
      shortName: teacher.shortName,
      displayName: teacher.displayName,
      cycleCommission: null,
      fullName: 'Непийвода Максим Васильович',
      firstName: 'Максим',
    };
    server.use(http.get(apiPath('/public/teachers'), () => HttpResponse.json([named])));
    const user = userEvent.setup();
    renderWithProviders(<LessonCell lesson={substituted} date={monday.date} />);
    await user.tab();
    await user.keyboard('{Enter}');
    const details = await screen.findByRole('dialog', { name: /Деталі: НДЕКСМ/ });
    // по батькові в картці не потрібне
    expect(await within(details).findByText(/^Непийвода\sМаксим$/)).toBeInTheDocument();
  });

  it('у сітці однофамільців розрізняють ініціали, а решту пишуть прізвищем', () => {
    const base = monday.lessons[0]!;
    const inna = { ...base, id: 'l-inna', pair: 1, teacher: { id: 't-inna', shortName: 'Овчар Ін.М.', displayName: 'Овчар' } };
    const ivan = { ...base, id: 'l-ivan', pair: 2, teacher: { id: 't-ivan', shortName: 'Овчар Ів.М.', displayName: 'Овчар' } };
    const days: DayDto[] = [{ ...monday, lessons: [inna, ivan] }];
    renderWithProviders(
      <GridNamesProvider days={days}>
        <LessonCell lesson={inna} />
        <LessonCell lesson={ivan} />
      </GridNamesProvider>,
    );
    expect(screen.getByText('Овчар Ін.М.')).toBeInTheDocument();
    expect(screen.getByText('Овчар Ів.М.')).toBeInTheDocument();

    // в іншому розкладі, де Овчар один, — лише прізвище
    renderWithProviders(
      <GridNamesProvider days={[{ ...monday, lessons: [inna] }]}>
        <LessonCell lesson={inna} />
      </GridNamesProvider>,
    );
    expect(screen.getByText('Овчар')).toBeInTheDocument();
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

  it('у останні хвилини пари пігулку «зараз» не накриває колонка часу наступного рядка', () => {
    // 10:39 — пігулка на межі 2 і 3 рядків: її рядок має стояти шаром вище за сусідні липкі клітинки
    renderWithProviders(<WeekGrid schedule={DEMO_WEEK} today="2026-11-09" at={10 * 60 + 39.8} />);
    const rowHeaders = within(screen.getByRole('table', { name: /Розклад 4КІ1/ })).getAllByRole('rowheader');
    expect(rowHeaders[1]).toHaveAttribute('data-now-row');
    expect(rowHeaders[1]!.className).toMatch(/(^|\s)z-\[6\]/);
    expect(rowHeaders[2]!.className).toMatch(/(^|\s)z-\[5\]/);
  });

  it('лінія «зараз» — лише в колонці сьогодні: у попередніх днях немає уривків над порожніми клітинками', () => {
    renderWithProviders(<WeekGrid schedule={DEMO_WEEK} today="2026-11-11" at={10 * 60} />);
    const table = screen.getByRole('table', { name: /Розклад 4КІ1/ });
    const lines = table.querySelectorAll<HTMLElement>('[data-now-line]');
    expect(lines).toHaveLength(1);
    expect(lines[0]).toHaveAttribute('data-now-line', 'strong');
  });

  it('лінія «зараз» не йде через текст заняття: лежить під вмістом, а в зайнятій клітинці — лише крапка на краю', () => {
    renderWithProviders(<WeekGrid schedule={DEMO_WEEK} today="2026-11-09" at={10 * 60} />);
    const table = screen.getByRole('table', { name: /Розклад 4КІ1/ });
    const line = table.querySelector<HTMLElement>('[data-now-line="strong"]');
    expect(line).not.toBeNull();
    // без z-index і перед вмістом у DOM: клітинка з тим самим тлом малюється поверх лінії
    const layer = line!.parentElement!;
    expect(layer.className).not.toMatch(/(^|\s)z-/);
    // лінія ковзає лише transform: шар висотою з клітинку зсунуто на пройдену частку пари
    expect(layer.style.transform).toMatch(/^translateY\(/);
    expect(layer.style.top).toBe('');
    const cell = line!.closest('td')!;
    const content = cell.querySelector(':scope > div');
    expect(content).not.toBeNull();
    expect(layer.compareDocumentPosition(content!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(content!.className).toMatch(/relative/);
    expect(content!.className).toMatch(/bg-today-wash/);
    // над заняттям від лінії лишається крапка на краю колонки, з ореолом «пара почалась»
    const marker = cell.querySelector('[data-now-marker]');
    expect(marker).not.toBeNull();
    expect(marker!.className).toMatch(/anim-halo/);
  });

  it('пʼятнична пара з іншим дзвінком показує свій час', () => {
    renderWithProviders(<WeekGrid schedule={DEMO_WEEK} today="2026-11-09" at={10 * 60} />);
    // у понеділок–четвер 1–3 пари мають той самий час, що й у пʼятницю, тож окремого часу немає
    expect(screen.queryByText('12:20–13:20')).not.toBeInTheDocument();
  });
});

/** Видимий текст (не прихований рядок для aria-live). */
const visibleText = (text: RegExp) => screen.getAllByText(text).find((node) => !node.closest('.sr-only'))!;

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

  it('у денному списку «зараз» не перетинає заняття: під час пари заповнюється лінія між часом і заняттям', () => {
    renderWithProviders(<DayList day={monday} bells={BELLS_MON_THU} today={monday.date} at={10 * 60 + 10} />);
    const current = screen.getAllByRole('listitem').find((item) => item.getAttribute('aria-current') === 'time')!;
    const progress = current.querySelector<HTMLElement>('[data-now-progress]');
    expect(progress).not.toBeNull();
    // росте лише transform (scaleY), без анімації висоти
    expect(progress!.style.transform).toBe('scaleY(0.5)');
    expect(progress!.style.height).toBe('');
    // жодної горизонтальної лінії на всю ширину рядка
    expect(current.querySelector('.inset-x-0')).toBeNull();
  });

  it('заголовок не рве «о» і час на різні рядки', () => {
    renderWithProviders(<NowIndicator bells={BELLS_MON_THU} lessonPairs={[1, 2, 3, 4]} at={8 * 60} variant="compact" />);
    const headline = visibleText(/Перша пара/);
    expect(headline.textContent).toBe('Перша пара о 08:30');
    expect(headline.querySelector('.whitespace-nowrap')?.textContent).toBe('о 08:30');
  });

  it('один рядок на сторінці розкладу тримає «о» з часом нерозривним пробілом', () => {
    renderWithProviders(<NowIndicator bells={BELLS_MON_THU} lessonPairs={[1, 2, 3, 4]} at={8 * 60} variant="inline" />);
    expect(visibleText(/Перша пара/).textContent).toBe('Перша пара о\u00a008:30 · за\u00a030\u00a0хв');
  });

  it('пояснює, коли пари закінчилися', () => {
    renderWithProviders(<NowIndicator bells={BELLS_MON_THU} lessonPairs={[1, 2]} at={15 * 60} />);
    expect(screen.getByText('На сьогодні все')).toBeInTheDocument();
    expect(screen.getByText(/Остання пара закінчилася о 10:40/)).toBeInTheDocument();
  });

  it('після останнього дзвінка доріжки дня немає: сірі смуги без підписів читались би як завантаження', () => {
    const { container, unmount } = renderWithProviders(
      <NowIndicator bells={BELLS_MON_THU} at={19 * 60} variant="hero" afterDay="Завтра перша о 08:30." />,
    );
    expect(screen.getByText('На сьогодні все')).toBeInTheDocument();
    expect(container.querySelector('[data-track-segment]')).toBeNull();
    expect(screen.queryByRole('img', { name: /Стан навчального дня/ })).not.toBeInTheDocument();
    unmount();

    // посеред дня доріжка на місці
    renderWithProviders(<NowIndicator bells={BELLS_MON_THU} at={10 * 60} variant="strip" />);
    expect(screen.getByRole('img', { name: /Стан навчального дня/ })).toBeInTheDocument();
  });
});

describe('пара, звільнена перенесенням, і зміна аудиторії', () => {
  // як 1ВЕ у четвер 19.11 на стенді: заняття з 2 пари перенесли на 1, на 2 нічого не стоїть; на 4 — інша аудиторія
  const [first, second] = monday.lessons as [(typeof monday.lessons)[number], (typeof monday.lessons)[number]];
  const moved = { ...first, id: 'moved', kind: 'MOVED' as const, original: { discipline: null, teacher: null, room: null, pair: 2 } };
  const roomChanged = {
    ...second,
    id: 'room',
    pair: 4,
    kind: 'ROOM_CHANGED' as const,
    original: { discipline: null, teacher: null, room: { id: 'r406', name: '406', type: 'CLASSROOM' as const }, pair: null },
  };
  const thursday: DayDto = { ...monday, lessons: [moved, { ...first, id: 'third', pair: 3, kind: 'REGULAR' }, roomChanged] };

  it('у денному списку — «Пари не буде» і куди перенесли, а не «Вікно — пари немає»', () => {
    renderWithProviders(<DayList day={thursday} bells={BELLS_MON_THU} today="2026-11-20" />);
    expect(screen.queryByText('Вікно — пари немає')).not.toBeInTheDocument();
    const vacated = document.querySelector('[data-vacated]') as HTMLElement;
    expect(vacated).toHaveTextContent('Пари не буде');
    expect(vacated).toHaveTextContent(`Було: ${first.discipline.shortName}`);
    expect(vacated).toHaveTextContent('перенесено на 1 пару');
    // зміна аудиторії називає себе словом стану, як заміна й додаткова пара
    expect(screen.getByRole('button', { name: /4 пара/ })).toHaveTextContent('Інша аудиторія');
  });

  it('у сітці тижня звільнена клітинка — «Пари не буде» в рядку 2 пари', () => {
    const week = { ...DEMO_WEEK, days: [thursday] };
    renderWithProviders(<WeekGrid schedule={week} today="2026-11-20" at={8 * 60} />);
    const rows = within(screen.getByRole('table')).getAllByRole('row');
    const pair2 = rows.find((row) => within(row).queryByRole('rowheader')?.textContent?.startsWith('2'));
    expect(pair2).toBeDefined();
    expect(pair2).toHaveTextContent('Пари не буде');
    expect(pair2).toHaveTextContent('перенесено на 1 пару');
  });
});
