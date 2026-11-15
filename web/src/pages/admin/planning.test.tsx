import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import type { GenerationJobDto } from '@/api/types';
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
    expect(
      within(grid).getAllByRole('button', {
        name: /Сучасні технології проєктування/,
      }),
    ).toHaveLength(2);
    // у тижневій сітці порожні клітинки без напису (пʼять колонок «пари немає» — шум); пошук — підказка-приклад
    expect(screen.queryByText('пари немає')).not.toBeInTheDocument();
    expect(screen.getByRole('searchbox', { name: 'Пошук у сітці' })).toHaveAttribute('placeholder', 'Пошук групи, напр. 4КІ1');
  });

  it('поки є жорсткі конфлікти, «Опублікувати» вимкнена, а шлях до конфліктів — одне речення (без глухого кута)', async () => {
    const user = userEvent.setup();
    renderWithProviders(<TimetablePage />, { route: '/admin/timetable?q=4КІ3' });
    // одне число в реченні; вікна й рвані дні — лише в «Подробицях»
    expect(await screen.findByText(/не дають опублікувати/, undefined, LONG)).toBeInTheDocument();
    expect(screen.queryByText(/вікон викладачів/)).not.toBeInTheDocument();
    // головна дія лишається публікацією, але вимкненою; третьої дороги до конфліктів у заголовку немає
    const publish = screen.getByRole('button', { name: 'Опублікувати' });
    expect(publish).toHaveAttribute('aria-disabled', 'true');
    expect(screen.queryByRole('button', { name: 'Показати конфлікти' })).not.toBeInTheDocument();
    await user.click(publish);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    // «показати» в реченні відкриває список конфліктів
    await user.click(screen.getByRole('button', { name: /Конфлікти: 75 жорстких/ }));
    expect(await screen.findByRole('dialog', { name: 'Конфлікти й вибрана пара' }, LONG)).toBeInTheDocument();
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
    // правок немає зовсім (не сірі кнопки): головна дія — почати чернетку з цієї версії
    expect(screen.queryByRole('button', { name: 'Додати пару' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Опублікувати' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Створити чернетку' })).toBeInTheDocument();
    // пару можна вибрати й роздивитись, вона не «вимкнена»
    const grid = screen.getByRole('grid', { name: /4КІ3/ });
    expect(within(grid).getAllByRole('button', { name: /Сучасні технології проєктування/ })[0]).not.toHaveAttribute('aria-disabled');
  });

  it('на телефоні сітка показує один день з перемикачем днів, а не пʼять стиснутих стовпців', async () => {
    const user = userEvent.setup();
    const original = window.matchMedia;
    window.matchMedia = (query: string) => ({ ...original(query), matches: query === '(max-width: 767px)' });
    try {
      renderWithProviders(<TimetablePage />, { route: '/admin/timetable?q=4КІ3' });
      const grid = await screen.findByRole('grid', { name: /4КІ3/ }, LONG);
      await user.click(screen.getByRole('radio', { name: 'вівторок' }));
      const headers = within(grid).getAllByRole('columnheader');
      expect(headers).toHaveLength(2);
      expect(headers[1]).toHaveTextContent('Вівторок');
      expect(
        within(grid).getAllByRole('button', {
          name: /Сучасні технології проєктування.*вівторок, 4 пара/,
        }),
      ).toHaveLength(1);
      // порожній рядок дня не стоїть пустим: «пари немає», як у публічному розкладі
      let labelled = 0;
      for (const day of screen.getAllByRole('radio', {
        name: /понеділок|вівторок|середа|четвер|ятниця/,
      })) {
        await user.click(day);
        labelled += within(grid).queryAllByText('пари немає').length;
      }
      expect(labelled).toBeGreaterThan(0);
    } finally {
      window.matchMedia = original;
    }
  });

  it('збій сервера — пояснення і «Спробувати ще раз», а не порожня сторінка', async () => {
    server.use(http.get(apiPath('/admin/timetable/versions'), () => problem({ status: 503 })));
    renderWithProviders(<TimetablePage />, { route: '/admin/timetable' });
    expect(await screen.findByText(/Версії розкладу не завантажено/, undefined, LONG)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Спробувати ще раз' })).toBeInTheDocument();
  });
});

describe('Генератор розкладу', () => {
  it('без запусків показує, що буде «Було», і не дає запустити «окремі курси» без курсу', async () => {
    const user = userEvent.setup();
    renderWithProviders(<GenerationPage />, { route: '/admin/generation' });
    expect(await screen.findByText(/Генератор у цьому семестрі ще не запускали/, undefined, LONG)).toBeInTheDocument();
    expect(await screen.findByText('Зараз у «Розклад I семестру 2026/2027»', undefined, LONG)).toBeInTheDocument();
    await user.click(screen.getByRole('radio', { name: 'Окремі курси' }));
    await user.click(screen.getByRole('button', { name: 'Запустити генерацію' }));
    expect(screen.getByText('Оберіть хоча б один курс')).toBeInTheDocument();
  });

  it('запуск — наприкінці панелі «Параметри»; правила — пріоритетами з типовими рівнями core', async () => {
    renderWithProviders(<GenerationPage />, { route: '/admin/generation' });
    const panel = (await screen.findByRole('heading', { name: 'Параметри' }, LONG)).closest('section');
    expect(panel).not.toBeNull();
    expect(within(panel as HTMLElement).getByRole('button', { name: 'Запустити генерацію' })).toBeInTheDocument();
    expect(screen.queryByRole('slider')).not.toBeInTheDocument();
    // типові ваги після оцінки на реальних запитах: вікна груп 30, 4-та пара 9, очікування між змінами 3
    expect(screen.getByRole('combobox', { name: 'Вікна груп' })).toHaveTextContent('Дуже важливо');
    expect(screen.getByRole('combobox', { name: 'Спортзал — як у базовій версії' })).toHaveTextContent('Дуже важливо');
    expect(screen.getByRole('combobox', { name: '4-та пара зміни' })).toHaveTextContent('Важливо');
    expect(screen.getByRole('combobox', { name: 'Очікування між змінами' })).toHaveTextContent('Важливо');
    // чесна підказка: 4-та пара зміни — не правило коледжу
    expect(screen.getByText(/Це не правило коледжу: у чинному розкладі 4-та пара зміни є в 57 % днів груп/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Типові пріоритети' })).not.toBeInTheDocument();
    // весь коледж за 1 хв на реальних даних виходив гіршим за чинний розклад — підказка біля ліміту
    expect(screen.getByText(/Для всього коледжу краще 5 хв/)).toBeInTheDocument();
  });

  it('422 «навантаження не вміщується» — список проблем під формою (B9)', async () => {
    const user = userEvent.setup();
    server.use(
      http.post(apiPath('/admin/generation/jobs'), () =>
        problem({
          status: 422,
          slug: 'business-rule',
          title: 'Порушено правило',
          code: 'GENERATION_LOAD_EXCEEDS_SLOTS',
          detail: 'Навантаження не вміщується в сітку занять (1).',
          extensions: {
            problems: [
              { kind: 'GROUP', id: 'g', label: '2ТК', requiredPairs: 20.5, availablePairs: 19, message: 'Група 2ТК: 20,5 пари на тиждень, а в її зміні 19 слотів.' },
            ],
          },
        }),
      ),
    );
    renderWithProviders(<GenerationPage />, { route: '/admin/generation' });
    await user.click(await screen.findByRole('button', { name: 'Запустити генерацію' }, LONG));
    const alert = await screen.findByRole('alert', { name: 'Навантаження не вміщується в сітку занять' }, LONG);
    expect(within(alert).getByText('Група 2ТК: 20,5 пари на тиждень, а в її зміні 19 слотів.')).toBeInTheDocument();
  });

  it('стара задача без нових полів відкривається; SOLVED з hard < 0 — «Готово з конфліктами» (B6, genfix §5)', async () => {
    const old: GenerationJobDto = {
      id: 'old-job',
      semesterId: 'any',
      status: 'SOLVED',
      createdAt: '2026-11-11T10:00:00Z',
      startedAt: '2026-11-11T10:00:00Z',
      finishedAt: '2026-11-11T10:10:00Z',
      bestScore: '-1hard/-2089soft',
      progress: [
        { t: 0, hard: -34, soft: -9000 },
        { t: 594, hard: -1, soft: -2089 },
      ],
      resultVersionId: null,
      baseVersionId: null,
      timeLimitSeconds: 600,
      analysis: [{ constraint: 'Чисельник і знаменник в одному слоті (бонус)', matchCount: 12, score: '0hard/120soft' }],
      metricsBefore: null,
      metricsAfter: { groupWindows: 1, teacherWindows: 123, brokenDays: 22, latePairs: 377, maxPairsPerDay: 4, perGroup: [] },
      error: null,
      requestedBy: null,
    };
    server.use(
      http.get(apiPath('/admin/generation/jobs'), () => HttpResponse.json([old])),
      http.get(apiPath('/admin/generation/jobs/:id'), () => HttpResponse.json(old)),
    );
    renderWithProviders(<GenerationPage />, { route: '/admin/generation' });
    expect(await screen.findByText('Готово з конфліктами', undefined, LONG)).toBeInTheDocument();
    expect(screen.getByText('З конфліктами')).toBeInTheDocument();
    expect(screen.getByText('Параметри цього запуску невідомі: сервер їх не зберіг.')).toBeInTheDocument();
    // без бази «Було» не вигадується з першої точки прогресу (34)
    const table = screen.getByRole('table', { name: 'Що вийшло' });
    expect(within(table).getByRole('row', { name: /Жорсткі конфлікти\s*1$/ })).toBeInTheDocument();
    // бонус — не «що лишилось»
    expect(screen.getByRole('heading', { name: 'Що вдалося' })).toBeInTheDocument();
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
