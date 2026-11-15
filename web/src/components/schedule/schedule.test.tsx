import { cleanup, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it, vi } from 'vitest';
import type { DayDto, PublicTeacherDto } from '@/api/types';
import { apiPath } from '@/mocks/kit';
import { server } from '@/mocks/server';
import { BELLS_MON_THU, DEMO_WEEK } from '@/pages/dev/demo-data';
import { formatTeacherName } from '@/lib/teacher-name';
import { renderWithProviders } from '@/test/render';
import { DayList } from './DayList';
import { GridNamesProvider } from './GridNames';
import { LessonCell } from './LessonCell';
import { LessonDetails } from './LessonDetails';
import { LessonLinksContext, type LessonLinks } from './lesson-links';
import { NowIndicator } from './NowIndicator';
import { pillShift } from './track-hint';
import { VacatedSlot } from './VacatedSlot';
import { nowPillShift } from './now-pill';
import { WeekGrid } from './WeekGrid';

const monday = DEMO_WEEK.days[0]!;
// підпис сітки так, як його бачить getByText: нерозривні пробіли — звичайними
const gridName = (teacher: Parameters<typeof formatTeacherName>[0]) => formatTeacherName(teacher, 'grid').replace(/\s/g, ' ');
const substituted = monday.lessons[1]!;

describe('LessonCell', () => {
  it('показує заміну поверх розкладу: новий предмет, бейдж і закреслений оригінал', () => {
    renderWithProviders(<LessonCell lesson={substituted} />);
    const cell = screen.getByRole('button', { name: /2 пара/ });
    // причина — тим самим словом, що й на картці, а не скороченням розпорядження «Зв.зам.»
    expect(cell).toHaveAccessibleName(/Заміна, замість: СТП, Кренцін\sМ\.\sД\.\. Причина: взаємна заміна\./);
    expect(cell).not.toHaveAccessibleName(/Зв\.зам/);
    expect(within(cell).getByText('НДЕКСМ')).toBeInTheDocument();
    expect(within(cell).getByText('Заміна')).toBeInTheDocument();
    expect(within(cell).getByText('СТП · Кренцін М. Д.').tagName).toBe('S');
  });

  it('відкриває повні деталі з клавіатури', async () => {
    const user = userEvent.setup();
    renderWithProviders(<LessonCell lesson={substituted} date={monday.date} />);
    await user.tab();
    await user.keyboard('{Enter}');
    const details = await screen.findByRole('dialog', { name: /Деталі: НДЕКСМ, 2 пара/ });
    expect(within(details).getByText('Надійність, діагностика та експлуатація компʼютерних систем і мереж')).toBeInTheDocument();
    // у картці — повне імʼя; імені в довіднику немає (налаштування повних імен вимкнене) — з ініціалами
    expect(within(details).getByText(/^Непийвода\sМ\.\sВ\.$/)).toBeInTheDocument();
    // код розпорядження в публічній картці розгорнуто словами
    expect(within(details).getByText('взаємна заміна')).toBeInTheDocument();
    // у сітці «Коли» — лише пара й час: день видно в самій сітці, парність тижня — у шапці сторінки
    expect(within(details).getByText(`2-га пара · ${substituted.startsAt}–${substituted.endsAt}`)).toBeInTheDocument();
    expect(within(details).queryByText(/чисельник|знаменник|Понеділок/)).not.toBeInTheDocument();
    // переходи — самі значення, окремого рядка посилань унизу немає
    expect(within(details).queryByText('Розклад викладача')).not.toBeInTheDocument();
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
    expect(code.parentElement).toHaveTextContent(`4КІ1 · ${gridName(lesson.teacher!)}`);
  });

  it('для групи викладач — тихий підпис без шрифту цифр', () => {
    const lesson = monday.lessons[0]!;
    renderWithProviders(<LessonCell lesson={lesson} perspective="group" />);
    expect(screen.getByText(gridName(lesson.teacher!))).not.toHaveClass('font-display');
  });
});

describe('картка заняття: значення-посилання', () => {
  const lesson = monday.lessons[0]!;
  const links = (self: LessonLinks['self'], search = '?view=week&date=2026-11-16'): LessonLinks => ({ self, search });

  it('викладач, аудиторія й група ведуть на свої розклади з тим самим поданням і днем', () => {
    renderWithProviders(
      <LessonLinksContext value={links(null)}>
        <LessonDetails lesson={lesson} context="grid" />
      </LessonLinksContext>,
    );
    const teacher = screen.getByRole('link', { name: /розклад викладача/ });
    expect(teacher).toHaveAttribute('href', `/t/${lesson.teacher!.id}?view=week&date=2026-11-16`);
    expect(screen.getByRole('link', { name: /розклад аудиторії/ })).toHaveAttribute('href', `/r/${lesson.room!.id}?view=week&date=2026-11-16`);
    const group = screen.getByRole('link', { name: '4КІ1, розклад групи' });
    expect(group).toHaveAttribute('href', `/g/${encodeURIComponent('4КІ1')}?view=week&date=2026-11-16`);
  });

  it('сутність сторінки — простий текст: на сторінці групи її код не посилання', () => {
    renderWithProviders(
      <LessonLinksContext value={links({ kind: 'group', id: lesson.group.id, label: lesson.group.code })}>
        <LessonDetails lesson={lesson} context="grid" />
      </LessonLinksContext>,
    );
    expect(screen.getByText('4КІ1').closest('a')).toBeNull();
    expect(screen.getAllByRole('link')).toHaveLength(2);
  });

  it('на сторінці викладача його імʼя не посилання, а група й аудиторія — так', () => {
    renderWithProviders(
      <LessonLinksContext value={links({ kind: 'teacher', id: lesson.teacher!.id, label: 'Непийвода' })}>
        <LessonDetails lesson={lesson} context="grid" />
      </LessonLinksContext>,
    );
    expect(screen.queryByRole('link', { name: /розклад викладача/ })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: /розклад аудиторії/ })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /розклад групи/ })).toBeInTheDocument();
  });

  it('на сторінці аудиторії її номер у картці є (клітинки його не повторюють), але не посиланням', () => {
    renderWithProviders(
      <LessonLinksContext value={links({ kind: 'room', id: lesson.room!.id, label: 'ауд. 301' })}>
        <LessonDetails lesson={{ ...lesson, room: null }} context="grid" />
      </LessonLinksContext>,
    );
    expect(screen.getByText('ауд. 301')).toBeInTheDocument();
    expect(screen.queryByText('не вказано')).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /розклад аудиторії/ })).not.toBeInTheDocument();
  });

  it('на сторінці лабораторії картка називає й тип своєї аудиторії, простим текстом', () => {
    const lab = { id: lesson.room!.id, name: '14', type: 'LAB' as const };
    renderWithProviders(
      <LessonLinksContext value={links({ kind: 'room', id: lab.id, label: 'ауд. 14', room: lab })}>
        <LessonDetails lesson={{ ...lesson, room: null }} context="grid" />
      </LessonLinksContext>,
    );
    expect(screen.getByText('ауд. 14')).toBeInTheDocument();
    expect(screen.getByText(/лабораторія/)).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /розклад аудиторії/ })).not.toBeInTheDocument();
  });

  it('перехід за посиланням повідомляє картку (шторка закривається)', async () => {
    const onNavigate = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<LessonDetails lesson={lesson} context="grid" onNavigate={onNavigate} />);
    await user.click(screen.getByRole('link', { name: /розклад групи/ }));
    expect(onNavigate).toHaveBeenCalledTimes(1);
  });

  it('посилання доступні з клавіатури: Tab по черзі проходить викладача, аудиторію й групу', async () => {
    const user = userEvent.setup();
    renderWithProviders(<LessonDetails lesson={lesson} context="grid" />);
    await user.tab();
    expect(screen.getByRole('link', { name: /розклад викладача/ })).toHaveFocus();
    await user.tab();
    expect(screen.getByRole('link', { name: /розклад аудиторії/ })).toHaveFocus();
    await user.tab();
    expect(screen.getByRole('link', { name: /розклад групи/ })).toHaveFocus();
    expect(screen.getByRole('link', { name: /розклад групи/ })).toHaveClass('focus-visible:outline-2');
  });
});

describe('картка заняття: «Коли»', () => {
  const lesson = monday.lessons[0]!;
  const time = `${lesson.startsAt}–${lesson.endsAt}`;

  it('поза сіткою (списки змін, пошук) — з повною датою', () => {
    renderWithProviders(<LessonDetails lesson={lesson} date={monday.date} />);
    expect(screen.getByText(new RegExp(`^Понеділок, \\d+ \\S+, 1-ша пара · ${time}$`))).toBeInTheDocument();
  });

  it('у сітці — лише пара й час, навіть коли дата відома', () => {
    renderWithProviders(<LessonDetails lesson={lesson} date={monday.date} context="grid" />);
    expect(screen.getByText(`1-ша пара · ${time}`)).toBeInTheDocument();
    expect(screen.queryByText(/Понеділок/)).not.toBeInTheDocument();
  });

  it('пара лише по знаменниках чи чисельниках має позначку, щотижнева — ні', () => {
    renderWithProviders(<LessonDetails lesson={{ ...lesson, parity: 'DENOMINATOR' }} context="grid" />);
    expect(screen.getByText('лише по знаменниках')).toBeInTheDocument();
    cleanup();

    renderWithProviders(<LessonDetails lesson={{ ...lesson, parity: 'NUMERATOR' }} context="grid" />);
    expect(screen.getByText('лише по чисельниках')).toBeInTheDocument();
    cleanup();

    renderWithProviders(<LessonDetails lesson={{ ...lesson, parity: 'BOTH' }} context="grid" />);
    expect(screen.queryByText(/^лише по/)).not.toBeInTheDocument();
    cleanup();

    // старіший core поля не має — позначки теж немає
    renderWithProviders(<LessonDetails lesson={lesson} context="grid" />);
    expect(screen.queryByText(/^лише по/)).not.toBeInTheDocument();
  });
});

describe('імена викладачів', () => {
  it('у картці — «Прізвище Імʼя По батькові», коли довідник віддає імʼя (налаштування повних імен увімкнене)', async () => {
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
    expect(await within(details).findByText(/^Непийвода\sМаксим\sВасильович$/)).toBeInTheDocument();
  });

  it('у сітці — завжди прізвище з ініціалами: однофамільців розрізняє коротка форма', () => {
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
    expect(screen.getByText('Овчар Ін. М.')).toBeInTheDocument();
    expect(screen.getByText('Овчар Ів. М.')).toBeInTheDocument();

    // і там, де Овчар один, — не голе прізвище
    renderWithProviders(
      <GridNamesProvider days={[{ ...monday, lessons: [inna] }]}>
        <LessonCell lesson={inna} />
      </GridNamesProvider>,
    );
    expect(screen.getAllByText('Овчар Ін. М.')).toHaveLength(2);
    expect(screen.queryByText('Овчар')).not.toBeInTheDocument();
  });
});

describe('імена з довідника в сітці', () => {
  it('коротка форма без ініціалів — «Прізвище І. Б.» з публічного довідника', async () => {
    const base = monday.lessons[0]!;
    const teacher = { id: 't-aleks', shortName: 'Алексєєнко', displayName: 'Алексєєнко' };
    const lesson = { ...base, id: 'l-aleks', teacher };
    const named: PublicTeacherDto = {
      ...teacher,
      cycleCommission: null,
      fullName: 'Алексєєнко Олександра Анатоліївна',
      firstName: 'Олександра',
    };
    server.use(http.get(apiPath('/public/teachers'), () => HttpResponse.json([named])));
    renderWithProviders(
      <GridNamesProvider days={[{ ...monday, lessons: [lesson] }]}>
        <LessonCell lesson={lesson} perspective="group" />
      </GridNamesProvider>,
    );
    expect(await screen.findByText('Алексєєнко О. А.')).toBeInTheDocument();
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

  it('пігулка «зараз» не ховає час пари: стає над чи під підписами, а на вільному місці — на точній хвилині', () => {
    // клітинка 0–100 px; підписи: номер 0–28 (ліворуч, не під пігулкою), початок 32–52, кінець 52–72
    const cell = { top: 0, bottom: 100 };
    const labels = [
      { top: 32, bottom: 52 },
      { top: 52, bottom: 72 },
    ];
    // 09:52 у парі 09:40–10:40 — центр на 20 % висоти, далеко від підписів: не зсуваємо
    expect(nowPillShift(20, cell, labels)).toBe(0);
    // на «09:40» — найближче вільне місце над стовпчиком підписів (32 + 3 − 10 = 25)
    expect(nowPillShift(36, cell, labels)).toBe(25 - 36);
    // на «10:40» — під ним (72 − 3 + 10 = 79)
    expect(nowPillShift(66, cell, labels)).toBe(79 - 66);
    // низька клітинка без місця — лишаємо як є, а не виносимо за межі рядка
    expect(nowPillShift(40, { top: 30, bottom: 74 }, labels)).toBe(0);
  });

  it('час пари, на якій стоїть «зараз», не зникає', () => {
    renderWithProviders(<WeekGrid schedule={DEMO_WEEK} today="2026-11-09" at={9 * 60 + 52} />);
    const rowHeaders = within(screen.getByRole('table', { name: /Розклад 4КІ1/ })).getAllByRole('rowheader');
    expect(rowHeaders[1]).toHaveTextContent(/09:40до 10:40/);
    expect(rowHeaders[1]!.querySelector('.opacity-0')).toBeNull();
  });

  it('у останні хвилини пари пігулку «зараз» не накриває колонка часу наступного рядка', () => {
    // 10:39 — пігулка на межі 2 і 3 рядків: її рядок має стояти шаром вище за сусідні липкі клітинки
    renderWithProviders(<WeekGrid schedule={DEMO_WEEK} today="2026-11-09" at={10 * 60 + 39.8} />);
    const rowHeaders = within(screen.getByRole('table', { name: /Розклад 4КІ1/ })).getAllByRole('rowheader');
    expect(rowHeaders[1]).toHaveAttribute('data-now-row');
    expect(rowHeaders[1]!.className).toMatch(/(^|\s)z-\[6\]/);
    expect(rowHeaders[2]!.className).toMatch(/(^|\s)z-\[5\]/);
  });

  it('«зараз» — лише в колонці сьогодні: у попередніх днях немає уривків над порожніми клітинками', () => {
    renderWithProviders(<WeekGrid schedule={DEMO_WEEK} today="2026-11-11" at={10 * 60} />);
    const table = screen.getByRole('table', { name: /Розклад 4КІ1/ });
    expect(table.querySelectorAll('[data-now-progress]')).toHaveLength(1);
    expect(table.querySelectorAll('[data-now-marker]')).toHaveLength(1);
    const todayColumn = within(table).getAllByRole('columnheader').findIndex((th) => th.getAttribute('aria-current') === 'date');
    const cell = table.querySelector('[data-now-progress]')!.closest('td')!;
    expect((cell.parentElement as HTMLTableRowElement).cells[todayColumn]).toBe(cell);
  });

  it('під час пари «зараз» видно й над заняттям: смуга на краю клітинки росте до пройденої частки, лінії через текст немає', () => {
    // 10:10 — середина 2 пари; у понеділок у цей час є заняття
    renderWithProviders(<WeekGrid schedule={DEMO_WEEK} today="2026-11-09" at={10 * 60 + 10} />);
    const table = screen.getByRole('table', { name: /Розклад 4КІ1/ });
    // горизонтальна лінія лягала б під картку (лишалась «сирітська» крапка) або через текст (читалась би закресленням)
    expect(table.querySelector('[data-now-line]')).toBeNull();
    const progress = table.querySelector<HTMLElement>('[data-now-progress]')!;
    expect(progress).not.toBeNull();
    // росте лише transform (scaleY), без анімації висоти
    expect(progress.style.transform).toBe('scaleY(0.5)');
    const bar = progress.parentElement!;
    // смуга — на лівому краї клітинки й над її вмістом (z-index), а не під карткою заняття
    expect(bar.className).toMatch(/(^|\s)start-0(\s|$)/);
    expect(bar.className).toMatch(/(^|\s)z-\[1\](\s|$)/);
    const cell = progress.closest('td')!;
    expect(within(cell).getByRole('button')).toBeInTheDocument();
    // крапка на точній хвилині — кінець смуги, з ореолом «пара почалась»
    const marker = cell.querySelector('[data-now-marker]');
    expect(marker).not.toBeNull();
    expect(marker!.className).toMatch(/anim-halo/);
  });

  it('у перерві «зараз» — лінія 2 px на межі рядків, поверх клітинки, і без смуги прогресу', () => {
    // 09:35 — перерва між 1 і 2 парою
    renderWithProviders(<WeekGrid schedule={DEMO_WEEK} today="2026-11-09" at={9 * 60 + 35} />);
    const table = screen.getByRole('table', { name: /Розклад 4КІ1/ });
    const line = table.querySelector<HTMLElement>('[data-now-line="strong"]')!;
    expect(line).not.toBeNull();
    expect(line.parentElement!.style.transform).toBe('translateY(0%)');
    expect(line.parentElement!.className).toMatch(/z-\[1\]/);
    expect(table.querySelector('[data-now-progress]')).toBeNull();
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
    expect(vacated).toHaveTextContent(`Було: ${first.discipline.name}`);
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

describe('NowIndicator: одна форма пари й пігулка в межах колонки', () => {
  it('рядок у заголовку сторінки пише «2 пара», як герой, а не «2-га пара»', () => {
    renderWithProviders(<NowIndicator bells={BELLS_MON_THU} lessonPairs={[1, 2, 3, 4]} at={10 * 60 + 17} variant="inline" />);
    expect(visibleText(/^Зараз/).textContent).toBe('Зараз 2\u00a0пара · ще 23\u00a0хв');
    cleanup();
    renderWithProviders(<NowIndicator bells={BELLS_MON_THU} lessonPairs={[1, 2, 3, 4]} at={9 * 60 + 35} variant="inline" />);
    expect(visibleText(/^Перерва/).textContent).toBe('Перерва · 2\u00a0пара о\u00a009:40');
  });

  it('пігулка «зараз» рухається сама й ніколи не виходить за колонку пари (сторінка не гортається вбік)', () => {
    // кінець пари: раніше невидима доріжка завширшки з колонку зсувалася на всю свою ширину й розширювала сторінку
    const { container } = renderWithProviders(
      <NowIndicator bells={BELLS_MON_THU} lessonPairs={[1, 2, 3, 4]} at={10 * 60 + 39.9} variant="strip" />,
    );
    expect(container.querySelector('[data-now-pill-track]')).toBeNull();
    const pill = container.querySelector<HTMLElement>('[data-now-pill]')!;
    expect(pill).not.toBeNull();
    // зсув — частка «колонка мінус пігулка» (56 px): при progress → 1 правий край пігулки на краю колонки
    expect(pill.style.transform).toMatch(/^translateX\(calc\(.*cqw.*rem\)\)$/);
    expect(pill.className).toMatch(/(^|\s)w-14(\s|$)/);
    const cell = pill.parentElement!;
    expect(cell).toHaveAttribute('data-now-pill-cell');
    expect(cell.className).toMatch(/(^|\s)@container(\s|$)/);
    expect(cell.className).toMatch(/(^|\s)overflow-x-clip(\s|$)/);
  });
});

describe('pillShift', () => {
  it('зсуває пігулку на частку «колонка мінус пігулка» й тримає її в колонці', () => {
    expect(pillShift(0)).toBe('translateX(calc((100cqw - 3.5rem) * 0))');
    expect(pillShift(0.5)).toBe('translateX(calc((100cqw - 3.5rem) * 0.5))');
    expect(pillShift(1)).toBe('translateX(calc((100cqw - 3.5rem) * 1))');
    // поза межами (годинник трохи випередив дзвінок) — не далі краю
    expect(pillShift(1.2)).toBe('translateX(calc((100cqw - 3.5rem) * 1))');
    expect(pillShift(-0.1)).toBe('translateX(calc((100cqw - 3.5rem) * 0))');
  });
});

describe('довга назва дисципліни в клітинці й картці', () => {
  const LONG = 'Надійність, діагностика та експлуатація компʼютерних систем і мереж';
  const longLesson = { ...monday.lessons[0]!, discipline: { ...monday.lessons[0]!.discipline, name: LONG, shortName: 'НДЕКСМ' } };

  it('у списку — до трьох рядків із переносом довгого слова, і зі словом стану теж (не без обмеження)', () => {
    renderWithProviders(<LessonCell lesson={{ ...longLesson, kind: 'SUBSTITUTED' }} variant="list" />);
    const title = screen.getByText(LONG);
    expect(title).toHaveAttribute('data-lesson-title');
    expect(title.className.split(/\s+/)).toEqual(expect.arrayContaining(['line-clamp-3', 'min-w-0', 'wrap-break-word']));
  });

  it('у сітці — скорочення у вузькій колонці, повна назва до двох рядків у широкій; повна — у картці при наведенні', async () => {
    const user = userEvent.setup();
    renderWithProviders(<LessonCell lesson={longLesson} variant="grid" />);
    const full = screen.getByText(LONG);
    expect(full.className.split(/\s+/)).toEqual(expect.arrayContaining(['@[12.5rem]/cell:line-clamp-2', 'wrap-break-word', 'min-w-0']));
    expect(screen.getByText('НДЕКСМ').className.split(/\s+/)).toContain('truncate');
    await user.hover(screen.getByRole('button', { name: /1 пара/ }));
    const details = await screen.findByRole('dialog', { name: /Деталі/ });
    expect(within(details).getByText(LONG)).toBeInTheDocument();
  });

  it('викладачу в денному списку — повна назва до двох рядків, а не обрізана одним', () => {
    renderWithProviders(<LessonCell lesson={longLesson} variant="list" perspective="teacher" />);
    const title = screen.getByText(LONG);
    expect(title.className.split(/\s+/)).toEqual(expect.arrayContaining(['line-clamp-2', 'wrap-break-word']));
    expect(title.className.split(/\s+/)).not.toContain('truncate');
  });

  it('звільнена перенесенням пара: повна назва в підказці, у списку — до двох рядків', () => {
    renderWithProviders(<VacatedSlot lessons={[{ ...longLesson, pair: 2, kind: 'MOVED', original: { discipline: null, teacher: null, room: null, pair: 2 } }]} fullTitle />);
    const struck = screen.getByText(LONG);
    expect(struck).toHaveAttribute('title', LONG);
    expect(struck.className.split(/\s+/)).toEqual(expect.arrayContaining(['line-clamp-2', 'wrap-break-word']));
  });
});

describe('«зараз» на сторінці дзвінків', () => {
  it('на телефоні — одна смуга поточної пари з часом, як у герої; доріжка дня — від md', () => {
    const { container } = renderWithProviders(<NowIndicator bells={BELLS_MON_THU} at={10 * 60 + 17} variant="strip" />);
    const phone = container.querySelector<HTMLElement>('[data-now-phone]')!;
    expect(phone).not.toBeNull();
    expect(phone.className.split(/\s+/)).toContain('md:hidden');
    expect(within(phone).getByText('09:40')).toBeInTheDocument();
    expect(within(phone).getByText('10:40')).toBeInTheDocument();
    const track = screen.getByRole('img', { name: /Стан навчального дня/ });
    expect(track.parentElement!.className.split(/\s+/)).toEqual(expect.arrayContaining(['hidden', 'md:block']));
  });

  it('до першої пари смуги на телефоні немає — порожнього блоку теж', () => {
    const { container } = renderWithProviders(<NowIndicator bells={BELLS_MON_THU} at={8 * 60} variant="strip" />);
    expect(container.querySelector('[data-now-phone]')).toBeNull();
  });
});
