import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { api } from '@/api/client';
import type { PeriodKindDto, PeriodKindImpactDto, PeriodKindRequest } from '@/api/types';
import { ConfirmDialogHost } from '@/components/ui/ConfirmDialog';
import { apiPath } from '@/mocks/kit';
import { server } from '@/mocks/server';
import { renderWithProviders } from '@/test/render';
import PeriodKindsPage from './PeriodKindsPage';

function renderPage() {
  return renderWithProviders(
    <>
      <PeriodKindsPage />
      <ConfirmDialogHost />
    </>,
    { route: '/admin/period-kinds' },
  );
}

const rowOf = async (code: string) => (await screen.findByRole('rowheader', { name: code })).closest('tr') as HTMLElement;

describe('сторінка «Види періодів»', () => {
  it('показує вбудовані види з ефектом і кількістю періодів; новий вид зберігається з псевдонімом і кольором', { timeout: 20_000 }, async () => {
    const user = userEvent.setup();
    renderPage();
    const ns = await rowOf('НС');
    expect(within(ns).getByText('Національний спротив')).toBeInTheDocument();
    expect(within(ns).getByText('Група не на парах')).toBeInTheDocument();
    expect(within(await rowOf('ЗУ')).getByText('Також: З')).toBeInTheDocument();
    expect(screen.getByText('15 видів')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Додати вид' }));
    const dialog = await screen.findByRole('dialog', { name: 'Новий вид періоду' });
    await user.type(within(dialog).getByRole('textbox', { name: /^Код/ }), 'ОП');
    await user.type(within(dialog).getByRole('textbox', { name: /^Назва/ }), 'Оборонна підготовка');
    await user.type(within(dialog).getByRole('textbox', { name: 'Новий псевдонім' }), 'ОБП{Enter}');
    expect(within(dialog).getByRole('list', { name: 'Псевдоніми виду' })).toHaveTextContent('ОБП');
    await user.click(within(dialog).getByRole('radio', { name: /бірюзовий/ }));
    // приклад примітки — за шаблоном практики, бо свого шаблону ще немає
    expect(within(dialog).getByText('4КІ1 – Оборонна підготовка з 16.11.26р. по 27.11.26 р., ауд. 401')).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Додати вид' }));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Новий вид періоду' })).not.toBeInTheDocument());

    const created = (await api.get<PeriodKindDto[]>('/admin/period-kinds')).find((k) => k.code === 'ОП');
    expect(created).toMatchObject({ name: 'Оборонна підготовка', effect: 'SUSPEND', aliases: ['ОБП'], color: '#33CCCC', builtin: false });
    expect(await rowOf('ОП')).toHaveTextContent('Доданий');
  });

  it('код, що вже є в іншого виду, — помилка сервера над полями', async () => {
    const user = userEvent.setup();
    renderPage();
    await rowOf('НС');
    await user.click(screen.getByRole('button', { name: 'Додати вид' }));
    const dialog = await screen.findByRole('dialog', { name: 'Новий вид періоду' });
    await user.type(within(dialog).getByRole('textbox', { name: /^Код/ }), 'з');
    await user.type(within(dialog).getByRole('textbox', { name: /^Назва/ }), 'Інші збори');
    await user.click(within(dialog).getByRole('button', { name: 'Додати вид' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Код «з» уже має вид «Збори».');
  });

  it('вбудований вид не пропонує «Видалити», лише «Вимкнути»', async () => {
    const user = userEvent.setup();
    renderPage();
    const row = await rowOf('Т');
    await user.click(within(row).getByRole('button', { name: 'Дії: вид Т' }));
    expect(await screen.findByRole('menuitem', { name: 'Вимкнути' })).toBeInTheDocument();
    expect(screen.queryByRole('menuitem', { name: 'Видалити' })).not.toBeInTheDocument();
  });

  it('використаний доданий вид не видаляється: пояснення сервера і «Вимкнути вид» замість видалення', async () => {
    await api.post<PeriodKindDto>('/admin/period-kinds', { code: 'ОБП', name: 'Оборонна підготовка', effect: 'SUSPEND' });
    server.use(
      http.delete(apiPath('/admin/period-kinds/:id'), () =>
        HttpResponse.json(
          { status: 409, detail: 'Вид «ОБП» мають 3 періоди календаря: спершу видаліть їх або змініть їм вид.' },
          { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );
    const user = userEvent.setup();
    renderPage();
    const row = await rowOf('ОБП');
    await user.click(within(row).getByRole('button', { name: 'Дії: вид ОБП' }));
    await user.click(await screen.findByRole('menuitem', { name: 'Видалити' }));
    await user.click(within(await screen.findByRole('alertdialog', { name: 'Видалити вид періоду?' })).getByRole('button', { name: 'Видалити' }));
    const blocked = await screen.findByRole('alertdialog', { name: 'Видалити не можна' });
    expect(blocked).toHaveTextContent('Вид «ОБП» мають 3 періоди календаря: спершу видаліть їх або змініть їм вид.');
    await user.click(within(blocked).getByRole('button', { name: 'Вимкнути вид' }));
    await waitFor(() => expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument());
    expect(await within(row).findByText(/вимкнено/)).toBeInTheDocument();
  });

  it('зміна ефекту: спершу пробне збереження з датами, групами й парами, потім підтвердження', { timeout: 20_000 }, async () => {
    const sent: Array<{ dryRun: boolean; body: PeriodKindRequest }> = [];
    server.use(
      http.put(apiPath('/admin/period-kinds/:id'), async ({ request }) => {
        const body = (await request.clone().json()) as PeriodKindRequest;
        sent.push({ dryRun: new URL(request.url).searchParams.get('dryRun') === 'true', body });
        // далі — справжній обробник мока
        return undefined;
      }),
    );
    const user = userEvent.setup();
    renderPage();
    await user.click(await rowOf('НП'));
    const dialog = await screen.findByRole('dialog', { name: 'НП · Практика' });
    await user.click(within(dialog).getByRole('radio', { name: /Лише позначка/ }));
    expect(within(dialog).getByText(/Зміна ефекту перебудує пари 1 періоду/)).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Зберегти' }));

    const confirm = await screen.findByRole('alertdialog', { name: 'Змінити ефект виду «НП»?' });
    expect(confirm).toHaveTextContent('«Група не на парах» → «Лише позначка» для 1 періоду');
    expect(confirm).toHaveTextContent('Групи: 2КІ3.');
    expect(sent).toEqual([{ dryRun: true, body: expect.objectContaining({ effect: 'INFO' }) as PeriodKindRequest }]);
    await user.click(within(confirm).getByRole('button', { name: 'Змінити ефект' }));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'НП · Практика' })).not.toBeInTheDocument());
    expect(sent.map((s) => s.dryRun)).toEqual([true, false]);
    expect(within(await rowOf('НП')).getByText('Лише позначка')).toBeInTheDocument();
  });

  it('зміну, яку сервер не прийме, показує у формі й не питає підтвердження', async () => {
    const impact: PeriodKindImpactDto = {
      kind: {} as PeriodKindDto,
      effectChanged: true,
      periods: 2,
      dates: ['2026-09-01'],
      groups: [],
      lessons: { added: 0, removed: 0, updated: 0 },
      conflicts: [{ kind: 'GROUP', severity: 'HARD', message: '4КІ1, 16.11, 2 пара: заміна вже підтверджена.', lessonIds: [], pair: 2 }],
      blockers: [],
      canApply: false,
    };
    server.use(http.put(apiPath('/admin/period-kinds/:id'), () => HttpResponse.json(impact)));
    const user = userEvent.setup();
    renderPage();
    await user.click(await rowOf('НС'));
    const dialog = await screen.findByRole('dialog', { name: 'НС · Національний спротив' });
    await user.click(within(dialog).getByRole('radio', { name: /^Вихідний/ }));
    await user.click(within(dialog).getByRole('button', { name: 'Зберегти' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      'Зміна розійдеться з живими замінами — спершу змініть їх. 4КІ1, 16.11, 2 пара: заміна вже підтверджена.',
    );
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
  });
});
