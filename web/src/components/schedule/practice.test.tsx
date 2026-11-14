import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { DayDto, PracticeDto, ScheduleDto } from '@/api/types';
import { DEMO_WEEK } from '@/pages/dev/demo-data';
import { renderWithProviders } from '@/test/render';
import { DayColumn } from './DayColumn';
import { DayList } from './DayList';
import { noteWithoutPractices, practiceTitle, supervisorsLine } from './practice';
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

describe('тексти практики', () => {
  it('група бачить «Практика: назва» і керівників, викладач — «Керівництво практикою» з групою', () => {
    expect(practiceTitle(PRACTICE, 'group')).toBe('Практика: Електрорадіомонтажна практика');
    expect(practiceTitle(PRACTICE, 'teacher')).toBe('Керівництво практикою: Електрорадіомонтажна практика, 2КІ3');
    expect(practiceTitle({ ...PRACTICE, groups: [], courses: [4] }, 'teacher')).toBe('Керівництво практикою: Електрорадіомонтажна практика, 4 курс');
    expect(plain(supervisorsLine(PRACTICE))).toBe('Керівники: Кренцін М. Д., Непийвода М. В.');
    expect(plain(supervisorsLine({ supervisors: PRACTICE.supervisors.slice(0, 1) }))).toBe('Керівник: Кренцін М. Д.');
    expect(supervisorsLine({ supervisors: [] })).toBeNull();
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
