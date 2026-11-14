import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import type { CalendarPeriod, CalendarPeriodRequest, CalendarSemester } from '@/api/calendar';
import { api } from '@/api/client';
import { KRENTSIN } from '@/mocks/handlers/public';
import { apiPath } from '@/mocks/kit';
import { server } from '@/mocks/server';
import { renderWithProviders } from '@/test/render';
import { PeriodFormDialog } from './PeriodForm';
import { PeriodsTable } from './PeriodsTable';

async function demoPractice() {
  const semester = (await api.get<CalendarSemester[]>('/admin/semesters')).find((s) => s.active) as CalendarSemester;
  const periods = await api.get<CalendarPeriod[]>('/admin/calendar-periods', { semesterId: semester.id });
  return { semester, practice: periods.find((p) => p.kind === 'PRACTICE') as CalendarPeriod };
}

describe('керівники практики в календарі', () => {
  it('у таблиці практика без керівника позначена «керівника не призначено», з керівником — його імʼя', async () => {
    const { semester, practice } = await demoPractice();
    const { unmount } = renderWithProviders(<PeriodsTable semester={semester} opened={null} onOpen={() => undefined} />);
    const row = (await screen.findByText(practice.title)).closest('tr') as HTMLElement;
    expect(within(row).getByText('керівника не призначено')).toBeInTheDocument();
    unmount();

    server.use(
      http.get(apiPath('/admin/calendar-periods'), () =>
        HttpResponse.json([{ ...practice, supervisorIds: [KRENTSIN.id], supervisors: [KRENTSIN] }]),
      ),
    );
    renderWithProviders(<PeriodsTable semester={semester} opened={null} onOpen={() => undefined} />);
    expect(await screen.findByText(`Керівник: ${KRENTSIN.shortName}`)).toBeInTheDocument();
  });

  it('форма показує призначених керівників, дає прибрати й зберігає supervisorIds', async () => {
    const { semester, practice } = await demoPractice();
    let sent: CalendarPeriodRequest | null = null;
    server.use(
      http.put(apiPath('/admin/calendar-periods/:id'), async ({ request }) => {
        sent = (await request.json()) as CalendarPeriodRequest;
        return HttpResponse.json({ ...practice, supervisorIds: [], supervisors: [], version: practice.version + 1 });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(
      <PeriodFormDialog
        semester={semester}
        period={{ ...practice, supervisorIds: [KRENTSIN.id], supervisors: [KRENTSIN] }}
        open
        onOpenChange={() => undefined}
      />,
    );
    const chosen = await screen.findByRole('list', { name: 'Вибрані керівники' });
    expect(within(chosen).getByText(KRENTSIN.shortName)).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: `Прибрати керівника ${KRENTSIN.shortName}` }));
    expect(screen.getByText('Керівника не призначено.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Зберегти' }));
    await expect.poll(() => sent).not.toBeNull();
    expect(sent).toMatchObject({ kind: 'PRACTICE', supervisorIds: [] });
  });
});
