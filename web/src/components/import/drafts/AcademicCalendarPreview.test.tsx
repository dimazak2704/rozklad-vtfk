import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import type { ImportJob, ImportOptions } from '@/api/import';
import { academicCalendarDraft } from '@/mocks/handlers/calendar-import';
import { apiPath } from '@/mocks/kit';
import { server } from '@/mocks/server';
import { renderWithProviders } from '@/test/render';
import { ApplyPanel } from '../ApplyPanel';
import { AcademicCalendarPreview } from './AcademicCalendarPreview';

function calendarJob(patch: Partial<ImportJob> = {}): ImportJob {
  const { report, draft } = academicCalendarDraft(null);
  return {
    id: 'job-cal',
    kind: 'ACADEMIC_CALENDAR_XLSX',
    fileName: 'Графік 2026-2027.xlsx',
    fileType: 'XLSX',
    fileSize: 1000,
    status: 'NEEDS_REVIEW',
    createdAt: '2026-11-15T08:00:00Z',
    updatedAt: '2026-11-15T08:00:00Z',
    summary: { recognized: 5, unknown: 0, conflicts: 1, warnings: 2 },
    report,
    draft,
    canApply: false,
    version: 2,
    ...patch,
  };
}

describe('чернетка графіка освітнього процесу', () => {
  it('показує підсумок перевірки core, коди з видами й невідомий код, періоди з тим, що з ними буде', () => {
    const job = calendarJob();
    renderWithProviders(<AcademicCalendarPreview job={job} draft={academicCalendarDraft(null).draft} />);
    expect(screen.getByText('Буде створено 3, оновлено 0, видалено 0, без змін 1.')).toBeInTheDocument();
    expect(screen.getByText(/Невідомі коди: «Ф»/)).toBeInTheDocument();
    const codes = screen.getByRole('list', { name: 'Коди клітинок' });
    expect(within(codes).getByRole('button', { name: /НС Національний спротив · група не на парах/ })).toBeInTheDocument();
    expect(within(codes).getByRole('button', { name: /Ф невідомий/ })).toBeInTheDocument();
    const row = screen.getByText('4КІ1').closest('tr') as HTMLElement;
    expect(row).toHaveTextContent('Основи національного спротиву, ауд. 401');
    expect(row).toHaveTextContent('без змін');
  });

  it('період цілком поза семестрами системи: «поза семестром, не імпортується» замість «—»', () => {
    const { draft } = academicCalendarDraft(null);
    const check = draft.check as NonNullable<typeof draft.check>;
    const january = draft.periods.find((p) => p.groupCodes?.includes('4ЕА1'))!;
    const outside = {
      ...check,
      periods: check.periods.filter((p) => p.sourceKey !== january.sourceKey),
      outOfSemester: [
        { sourceKey: january.sourceKey, kindCode: 'НС', startsOn: january.startsOn, endsOn: january.endsOn, groupCodes: ['4ЕА1'], kept: [] },
      ],
    };
    renderWithProviders(<AcademicCalendarPreview job={calendarJob()} draft={{ ...draft, check: outside }} />);
    expect(screen.getByText('4ЕА1').closest('tr')).toHaveTextContent('поза семестром, не імпортується');
  });

  it('період за курсами названо «1, 2 курси», одного курсу — «4 курс»', () => {
    const { draft } = academicCalendarDraft(null);
    const first = draft.periods[0]!;
    const byCourses = [
      { ...first, sourceKey: 'c12', scope: 'COURSES' as const, groupCodes: null, courses: [1, 2] },
      { ...first, sourceKey: 'c4', scope: 'COURSES' as const, groupCodes: null, courses: [4] },
    ];
    renderWithProviders(<AcademicCalendarPreview job={calendarJob()} draft={{ ...draft, periods: byCourses }} />);
    expect(screen.getByText('1, 2 курси')).toBeInTheDocument();
    expect(screen.getByText('4 курс')).toBeInTheDocument();
  });

  it('«Перевірити знову» зберігає пропущені коди в параметрах', async () => {
    let sent: { version: number; options: ImportOptions } | null = null;
    server.use(
      http.put(apiPath('/import/jobs/:id/options'), async ({ request }) => {
        sent = (await request.json()) as { version: number; options: ImportOptions };
        return HttpResponse.json(calendarJob({ status: 'READY', canApply: true }));
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<AcademicCalendarPreview job={calendarJob()} draft={academicCalendarDraft(null).draft} />);
    await user.click(screen.getByRole('button', { name: /Ф невідомий/ }));
    expect(screen.getByText('Усі коди розпізнано. Щоб пропустити код, натисніть його: його клітинки не імпортуються.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Перевірити знову' }));
    await expect.poll(() => sent).toEqual({ version: 2, options: { skipCodes: ['Ф'] } });
  });

  it('після застосування — підсумок календаря й посилання на календар', () => {
    const { draft } = academicCalendarDraft({ skipCodes: ['Ф'] });
    const calendar = { ...(draft.check as NonNullable<typeof draft.check>), dryRun: false, skippedManual: 1 };
    renderWithProviders(<ApplyPanel job={calendarJob({ status: 'APPLIED', result: { calendar } })} onShowTab={() => undefined} />);
    expect(screen.getByText('Календар 2026/2027: створено 3, без змін 1.')).toBeInTheDocument();
    expect(screen.getByText('Змінені вручну не чіпали: 1.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Відкрити календар' })).toHaveAttribute('href', '/admin/calendar');
  });
});
