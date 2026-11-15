import { QueryClient } from '@tanstack/react-query';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import type { CalendarPeriod, CalendarPeriodRequest, CalendarSemester } from '@/api/calendar';
import { api } from '@/api/client';
import { qk } from '@/api/queryKeys';
import type { PeriodKindDto } from '@/api/types';
import { KRENTSIN } from '@/mocks/handlers/public';
import { apiPath } from '@/mocks/kit';
import { server } from '@/mocks/server';
import { renderWithProviders } from '@/test/render';
import { PeriodFormDialog } from './PeriodForm';
import { PeriodsTable } from './PeriodsTable';

async function demoPeriods() {
  const semester = (await api.get<CalendarSemester[]>('/admin/semesters')).find((s) => s.active) as CalendarSemester;
  const periods = await api.get<CalendarPeriod[]>('/admin/calendar-periods', { semesterId: semester.id });
  const kinds = await api.get<PeriodKindDto[]>('/admin/period-kinds');
  const byCode = (code: string) => kinds.find((k) => k.code === code) as PeriodKindDto;
  return {
    semester,
    kinds,
    byCode,
    practice: periods.find((p) => p.periodKind?.code === 'НП') as CalendarPeriod,
    ons: periods.find((p) => p.periodKind?.code === 'НС') as CalendarPeriod,
  };
}

/** Перехоплює PUT періоду й віддає збережене тіло. */
function capturePut(period: CalendarPeriod) {
  const box: { sent: CalendarPeriodRequest | null } = { sent: null };
  server.use(
    http.put(apiPath('/admin/calendar-periods/:id'), async ({ request }) => {
      box.sent = (await request.json()) as CalendarPeriodRequest;
      return HttpResponse.json({ ...period, version: period.version + 1 });
    }),
  );
  return box;
}

describe('керівники практики в календарі', () => {
  it('у таблиці практика без керівника позначена «керівника не призначено», з керівником — його імʼя', async () => {
    const { semester, practice } = await demoPeriods();
    const { unmount } = renderWithProviders(<PeriodsTable semester={semester} opened={null} onOpen={() => undefined} />);
    const row = (await screen.findByText(practice.title)).closest('tr') as HTMLElement;
    expect(within(row).getByText('керівника не призначено')).toHaveClass('sr-only');
    // під назвою немає видимого рядка з самим «—»
    expect(within(row).queryByText('—')).not.toBeInTheDocument();
    unmount();

    server.use(
      http.get(apiPath('/admin/calendar-periods'), () =>
        HttpResponse.json([{ ...practice, supervisorIds: [KRENTSIN.id], supervisors: [KRENTSIN] }]),
      ),
    );
    renderWithProviders(<PeriodsTable semester={semester} opened={null} onOpen={() => undefined} />);
    expect(await screen.findByText(`Керівник: ${KRENTSIN.shortName}`)).toBeInTheDocument();
  });

  it('форма показує призначених керівників, дає прибрати й зберігає supervisorIds з kindId', async () => {
    const { semester, practice } = await demoPeriods();
    const box = capturePut(practice);
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
    // початок — «Від»: кирилична «З» біля дати читалася як цифра 3
    expect(screen.getByLabelText(/^Від/)).toHaveValue(practice.startsOn.split('-').reverse().join('.'));

    await user.click(screen.getByRole('button', { name: `Прибрати керівника ${KRENTSIN.shortName}` }));
    expect(screen.getByText('Керівника не призначено.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Зберегти' }));
    await expect.poll(() => box.sent).not.toBeNull();
    expect(box.sent).toMatchObject({ kindId: practice.kindId, supervisorIds: [] });
    expect(box.sent).not.toHaveProperty('kind');
  });
});

describe('види періодів у формі', () => {
  it('у таблиці НС — вид з довідника й аудиторія під назвою', async () => {
    const { semester, ons } = await demoPeriods();
    renderWithProviders(<PeriodsTable semester={semester} opened={null} onOpen={() => undefined} />);
    const row = (await screen.findByText(ons.title)).closest('tr') as HTMLElement;
    expect(within(row).getByText('Національний спротив')).toBeInTheDocument();
    expect(within(row).getByText(', ауд. 401')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Види періодів' })).toHaveAttribute('href', '/admin/period-kinds');
  });

  it('НС з графіка: аудиторія 401, попередження про імпорт, «Скласти» за шаблоном виду', async () => {
    const { semester, ons } = await demoPeriods();
    const user = userEvent.setup();
    renderWithProviders(<PeriodFormDialog semester={semester} period={{ ...ons, orderNote: null }} open onOpenChange={() => undefined} />);
    expect(await screen.findByText(/Період з графіка освітнього процесу/)).toBeInTheDocument();
    expect(await screen.findByRole('combobox', { name: 'Аудиторія' })).toHaveTextContent('401');
    await user.click(await screen.findByRole('button', { name: 'Скласти з полів' }));
    expect(screen.getByRole('textbox', { name: 'Примітка в розпорядженні' })).toHaveValue(
      '4КІ1 – Основи національного спротиву з 16.11.26р. по 27.11.26 р., ауд. 401',
    );
  });

  it('вид з ефектом «лише позначка» ховає аудиторію й керівників і не шле їх', async () => {
    const { semester, ons, byCode } = await demoPeriods();
    const box = capturePut(ons);
    const user = userEvent.setup();
    renderWithProviders(
      <PeriodFormDialog semester={semester} period={{ ...ons, supervisorIds: [KRENTSIN.id], supervisors: [KRENTSIN] }} open onOpenChange={() => undefined} />,
    );
    await screen.findByRole('combobox', { name: 'Аудиторія' });
    await user.click(screen.getByRole('combobox', { name: /^Вид/ }));
    await user.click(await screen.findByRole('option', { name: /Резерв навчального часу/ }));
    expect(screen.queryByRole('combobox', { name: 'Аудиторія' })).not.toBeInTheDocument();
    expect(screen.queryByRole('list', { name: 'Вибрані керівники' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Зберегти' }));
    await expect.poll(() => box.sent).not.toBeNull();
    expect(box.sent).toMatchObject({ kindId: byCode('Р').id, roomId: null, supervisorIds: [], studyMode: null });
  });

  it('новий період: вид за замовчуванням — практика; вид «форма навчання» вимагає режим', async () => {
    const { semester } = await demoPeriods();
    const user = userEvent.setup();
    renderWithProviders(<PeriodFormDialog semester={semester} period={null} open onOpenChange={() => undefined} />);
    const kind = await screen.findByRole('combobox', { name: /^Вид/ });
    await expect.poll(() => kind.textContent).toContain('Практика');
    await user.click(kind);
    await user.click(await screen.findByRole('option', { name: /Форма навчання/ }));
    expect(screen.getByRole('radiogroup', { name: 'Форма навчання' })).toBeInTheDocument();
    expect(screen.queryByRole('combobox', { name: 'Аудиторія' })).not.toBeInTheDocument();
  });

  it('новий період, коли довідник видів уже в кеші (форму відкрили вдруге): вид за замовчуванням — теж практика', async () => {
    const { semester, kinds } = await demoPeriods();
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
    queryClient.setQueryData(qk.admin.calendar.periodKinds({}), kinds);
    renderWithProviders(<PeriodFormDialog semester={semester} period={null} open onOpenChange={() => undefined} />, { queryClient });
    const kind = await screen.findByRole('combobox', { name: /^Вид/ });
    await expect.poll(() => kind.textContent).toContain('Практика');
  });

  it('відмова сервера про вимкнений вид — під полем «Вид»', async () => {
    const { semester, ons } = await demoPeriods();
    server.use(
      http.put(apiPath('/admin/calendar-periods/:id'), () =>
        HttpResponse.json(
          { status: 400, detail: 'Перевірте виділені поля.', errors: [{ field: 'kindId', message: 'Вид «Збори» вимкнено: увімкніть його в «Видах періодів» або виберіть інший.' }] },
          { status: 400, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<PeriodFormDialog semester={semester} period={ons} open onOpenChange={() => undefined} />);
    await screen.findByRole('combobox', { name: 'Аудиторія' });
    await user.clear(screen.getByRole('textbox', { name: /^Назва/ }));
    await user.type(screen.getByRole('textbox', { name: /^Назва/ }), 'Інша назва');
    await user.click(screen.getByRole('button', { name: 'Зберегти' }));
    expect(await screen.findByText('Вид «Збори» вимкнено: увімкніть його в «Видах періодів» або виберіть інший.')).toBeInTheDocument();
  });
});
