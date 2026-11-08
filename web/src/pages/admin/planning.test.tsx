import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http } from 'msw';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { PUBLISHED_VERSION_ID } from '@/mocks/fixtures';
import { apiPath, problem } from '@/mocks/kit';
import { server } from '@/mocks/server';
import { renderWithProviders } from '@/test/render';
import GenerationPage from './GenerationPage';
import TimetablePage from './TimetablePage';

const LONG = { timeout: 10_000 };

// сторінки важкі (тисяча пар, перший прогін компілює модулі) — більше часу на тест
vi.setConfig({ testTimeout: 30_000 });

beforeEach(() => {
  // jsdom не вміє прокручувати вікно, а віртуалізатор дошки це робить
  vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined);
});

describe('Базовий розклад', () => {
  it('відкриває демо-чернетку з метриками, сіткою групи й конфліктами', async () => {
    renderWithProviders(<TimetablePage />, { route: '/admin/timetable?q=4КІ3' });
    expect(await screen.findByRole('grid', { name: /4КІ3/ }, LONG)).toBeInTheDocument();
    expect(screen.getByRole('combobox', { name: 'Версія розкладу' })).toHaveTextContent('Чернетка: рівні дні 4КІ1');
    // на вузькому екрані (jsdom) конфлікти — за кнопкою, що відкриває шторку
    expect(await screen.findByRole('button', { name: /Конфлікти: 75 жорстких/ }, LONG)).toBeInTheDocument();
    expect(screen.getByLabelText('Рвані дні: 32')).toBeInTheDocument();
    const grid = screen.getByRole('grid', { name: /4КІ3/ });
    expect(within(grid).getAllByRole('button', { name: /Сучасні технології проєктування/ })).toHaveLength(2);
  });

  it('«Перемістити» з клавіатури: сервер пояснює конфлікт, кнопка неактивна', async () => {
    const user = userEvent.setup();
    renderWithProviders(<TimetablePage />, { route: '/admin/timetable?q=4КІ3' });
    const grid = await screen.findByRole('grid', { name: /4КІ3/ }, LONG);
    const stp = within(grid).getByRole('button', { name: /Сучасні технології проєктування.*вівторок, 4 пара/ });
    stp.focus();
    await user.keyboard('m');
    const dialog = await screen.findByRole('dialog', { name: 'Перемістити пару' });
    await user.click(within(dialog).getByRole('radio', { name: 'Пн' }));
    expect(await within(dialog).findByText(/Кренцін веде одночасно 4КІ2 — СТП і 4КІ3 — СТП/, undefined, LONG)).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: 'Перемістити' })).toBeDisabled();
    await user.click(within(dialog).getByRole('radio', { name: 'Ср' }));
    expect(await within(dialog).findByText('Слот вільний — можна переносити', undefined, LONG)).toBeInTheDocument();
  });

  it('опублікована версія — лише перегляд, з пропозицією створити чернетку', async () => {
    renderWithProviders(<TimetablePage />, { route: `/admin/timetable?q=4КІ3&version=${PUBLISHED_VERSION_ID}` });
    expect(await screen.findByText(/Ця версія опублікована й діє/, undefined, LONG)).toBeInTheDocument();
    await screen.findByRole('grid', { name: /4КІ3/ }, LONG);
    expect(screen.getByRole('button', { name: 'Додати пару' })).toBeDisabled();
    expect(screen.queryByRole('button', { name: 'Опублікувати' })).not.toBeInTheDocument();
  });

  it('збій сервера — пояснення і «Спробувати ще раз», а не порожня сторінка', async () => {
    server.use(http.get(apiPath('/admin/timetable/versions'), () => problem({ status: 503 })));
    renderWithProviders(<TimetablePage />, { route: '/admin/timetable' });
    expect(await screen.findByText('Версії розкладу не завантажено', undefined, LONG)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Спробувати ще раз' })).toBeInTheDocument();
  });
});

describe('Генератор розкладу', () => {
  it('без запусків показує, що буде «Було», і не дає запустити «окремі курси» без курсу', async () => {
    const user = userEvent.setup();
    renderWithProviders(<GenerationPage />, { route: '/admin/generation' });
    expect(await screen.findByText('Генератор у цьому семестрі ще не запускали', undefined, LONG)).toBeInTheDocument();
    expect(await screen.findByText('Зараз у «Розклад I семестру 2026/2027»', undefined, LONG)).toBeInTheDocument();
    await user.click(screen.getByRole('radio', { name: 'Окремі курси' }));
    await user.click(screen.getByRole('button', { name: 'Запустити генерацію' }));
    expect(screen.getByText('Оберіть хоча б один курс')).toBeInTheDocument();
  });

  it('запуск показує задачу в черзі, а потім хід розвʼязання', async () => {
    const user = userEvent.setup();
    renderWithProviders(<GenerationPage />, { route: '/admin/generation' });
    await user.click(await screen.findByRole('button', { name: 'Запустити генерацію' }, LONG));
    expect((await screen.findAllByText(/^(У черзі|Розвʼязується)$/, undefined, LONG)).length).toBeGreaterThan(0);
    await waitFor(() => expect(screen.getByRole('button', { name: 'Запустити генерацію' })).toBeDisabled());
    expect(screen.getByRole('button', { name: 'Скасувати' })).toBeInTheDocument();
  });
});
