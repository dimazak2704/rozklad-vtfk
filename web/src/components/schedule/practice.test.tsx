import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { DayDto, PracticeDto, ScheduleDto } from '@/api/types';
import { DEMO_WEEK } from '@/pages/dev/demo-data';
import { renderWithProviders } from '@/test/render';
import { DayColumn } from './DayColumn';
import { DayList } from './DayList';
import { blocksLabel, blockTitle, noteWithoutPractices, supervisorsLine } from './practice';
import { WeekGrid } from './WeekGrid';

const PRACTICE: PracticeDto = {
  periodId: 'p-1',
  title: 'Електрорадіомонтажна практика',
  startsOn: '2026-10-19',
  endsOn: '2026-10-30',
  supervisors: [
    { id: 't-1', shortName: 'Кренцін М.Д.', displayName: 'Кренцін' },
    { id: 't-2', shortName: 'Непийвода М.В.', displayName: 'Непийвода' },
  ],
  groups: [{ id: 'g-1', code: '2КІ3', course: 2 }],
  courses: [],
};

const monday = DEMO_WEEK.days[0]!;
// група на практиці: заняття прибрані, день для неї не навчальний, примітка — назва практики
const practiceDay: DayDto = { ...monday, studyDay: false, note: PRACTICE.title, lessons: [], practices: [PRACTICE] };
// «Кренцін М. Д.» з нерозривними пробілами — у тексті порівнюємо зі звичайними
const plain = (text: string | null) => text?.replace(/\s/g, ' ') ?? null;

// НС 4КІ1 з графіка 2026/27: вид «група не на парах» з аудиторією 401 (рішення власника)
const ONS: PracticeDto = {
  periodId: 'p-ns',
  title: 'Основи національного спротиву',
  startsOn: '2026-11-16',
  endsOn: '2026-11-27',
  supervisors: [],
  groups: [{ id: 'g-4ki1', code: '4КІ1', course: 4 }],
  courses: [],
  kind: { code: 'НС', name: 'Національний спротив', color: null, effect: 'SUSPEND' },
  room: { id: 'r-401', name: '401', type: 'CLASSROOM' },
};

describe('тексти практики', () => {
  it('група бачить «Практика: назва» і керівників, викладач — «Керівництво практикою» з групою', () => {
    expect(blockTitle(PRACTICE, 'group')).toBe('Практика: Електрорадіомонтажна практика');
    expect(blockTitle(PRACTICE, 'teacher')).toBe('Керівництво практикою: Електрорадіомонтажна практика, 2КІ3');
    expect(blockTitle({ ...PRACTICE, groups: [], courses: [4] }, 'teacher')).toBe('Керівництво практикою: Електрорадіомонтажна практика, 4 курс');
    expect(plain(supervisorsLine(PRACTICE))).toBe('Керівники: Кренцін М. Д., Непийвода М. В.');
    expect(plain(supervisorsLine({ supervisors: PRACTICE.supervisors.slice(0, 1) }))).toBe('Керівник: Кренцін М. Д.');
    expect(supervisorsLine({ supervisors: [] })).toBeNull();
  });

  it('загальний блок: «{Вид}: назва, ауд. 401»; керівник — «Керівництво: {Вид}: …»; практика від нового core — як раніше', () => {
    expect(blockTitle(ONS, 'group')).toBe('Національний спротив: Основи національного спротиву, ауд. 401');
    expect(blockTitle(ONS, 'teacher')).toBe('Керівництво: Національний спротив: Основи національного спротиву, 4КІ1, ауд. 401');
    const practiceKind = { ...PRACTICE, kind: { code: 'НП', name: 'Практика', color: null, effect: 'SUSPEND' as const }, room: null };
    expect(blockTitle(practiceKind, 'group')).toBe('Практика: Електрорадіомонтажна практика');
    expect(blockTitle(practiceKind, 'teacher')).toBe('Керівництво практикою: Електрорадіомонтажна практика, 2КІ3');
    expect(blocksLabel([PRACTICE, practiceKind], 'teacher')).toBe('Практика');
    expect(blocksLabel([ONS], 'group')).toBe('Національний спротив');
    expect(blocksLabel([ONS], 'teacher')).toBe('Керівництво');
    expect(blocksLabel([PRACTICE, ONS], 'teacher')).toBe('Керівництво');
    expect(blocksLabel([PRACTICE, ONS], 'group')).toBe('Без пар');
  });

  it('назву, що лише повторює вид (імпорт графіка: «Сесія», «Збори»), пишемо один раз', () => {
    const session: PracticeDto = {
      ...ONS,
      title: 'Сесія',
      groups: [{ id: 'g-4ki1', code: '4КІ1', course: 4 }],
      kind: { code: 'І', name: 'Сесія', color: null, effect: 'SUSPEND' },
      room: null,
    };
    expect(blockTitle(session, 'group')).toBe('Сесія');
    expect(blockTitle(session, 'teacher')).toBe('Керівництво: Сесія, 4КІ1');
    expect(blockTitle({ ...session, title: ' збори ', kind: { ...session.kind!, name: 'Збори' }, room: ONS.room }, 'group')).toBe(
      'Збори, ауд. 401',
    );
    // назва, що відрізняється від виду, лишається як є
    expect(blockTitle({ ...session, title: 'Сесія 4 курсу' }, 'group')).toBe('Сесія: Сесія 4 курсу');
  });

  it('керівник курсів бачить «4 курс» чи «1, 2 курси»', () => {
    expect(blockTitle({ ...ONS, groups: [], courses: [1, 2], room: null }, 'teacher')).toBe(
      'Керівництво: Національний спротив: Основи національного спротиву, 1, 2 курси',
    );
    expect(blockTitle({ ...PRACTICE, groups: [], courses: [1, 2] }, 'teacher')).toBe(
      'Керівництво практикою: Електрорадіомонтажна практика, 1, 2 курси',
    );
  });

  it('примітка не повторює назву практики, решту лишає', () => {
    expect(noteWithoutPractices({ note: PRACTICE.title }, [PRACTICE])).toBeNull();
    expect(noteWithoutPractices({ note: `${PRACTICE.title}; Сесія` }, [PRACTICE])).toBe('Сесія');
    expect(noteWithoutPractices({ note: PRACTICE.title }, [])).toBe(PRACTICE.title);
  });
});

describe('практика в розкладі', () => {
  it('день групи: блок практики з керівниками замість «Не навчальний день»', () => {
    renderWithProviders(<DayColumn day={practiceDay} />);
    expect(screen.getByText('Практика: Електрорадіомонтажна практика')).toBeInTheDocument();
    expect(screen.getByText(/^Керівники: Кренцін\sМ\.\sД\., Непийвода\sМ\.\sВ\.$/)).toBeInTheDocument();
    expect(screen.queryByText('Не навчальний день')).not.toBeInTheDocument();
  });

  it('день групи на НС: блок виду з аудиторією, список названо видом', () => {
    renderWithProviders(<DayList day={{ ...practiceDay, note: ONS.title, practices: [ONS] }} />);
    const block = screen.getByRole('list', { name: 'Національний спротив' });
    expect(block).toHaveTextContent('Національний спротив: Основи національного спротиву, ауд. 401');
    expect(screen.queryByText('Не навчальний день.')).not.toBeInTheDocument();
    // назва вже в блоці — у примітці дня її не повторюємо
    expect(screen.getAllByText(/Основи національного спротиву/)).toHaveLength(1);
  });

  it('без керівника публічно лише назва практики', () => {
    renderWithProviders(<DayList day={{ ...practiceDay, practices: [{ ...PRACTICE, supervisors: [] }] }} />);
    expect(screen.getByText('Практика: Електрорадіомонтажна практика')).toBeInTheDocument();
    expect(screen.queryByText(/Керівник/)).not.toBeInTheDocument();
    expect(screen.queryByText('Не навчальний день.')).not.toBeInTheDocument();
  });

  it('тиждень групи: практика в клітинці дня; тиждень керівника: рядок «Практика» над його парами', () => {
    const group: ScheduleDto = { ...DEMO_WEEK, days: [practiceDay, ...DEMO_WEEK.days.slice(1)] };
    const { unmount } = renderWithProviders(<WeekGrid schedule={group} />);
    expect(screen.getByText('Практика: Електрорадіомонтажна практика')).toBeInTheDocument();
    unmount();

    const teacher: ScheduleDto = {
      ...DEMO_WEEK,
      owner: { type: 'TEACHER', id: 't-1', label: 'Кренцін М.Д.' },
      days: [{ ...monday, practices: [PRACTICE] }, ...DEMO_WEEK.days.slice(1)],
    };
    renderWithProviders(<WeekGrid schedule={teacher} perspective="teacher" />);
    expect(screen.getByRole('rowheader', { name: 'Практика' })).toBeInTheDocument();
    expect(screen.getByText('Керівництво практикою: Електрорадіомонтажна практика, 2КІ3')).toBeInTheDocument();
  });
});
