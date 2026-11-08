import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { onlineManager } from '@tanstack/react-query';
import { http } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { OrderDto, SettingsDto } from '@/api/types';
import { apiPath, problem } from '@/mocks/kit';
import { server } from '@/mocks/server';
import { resetMockStores } from '@/mocks/store';
import ChangesPage from '@/pages/admin/ChangesPage';
import { renderWithProviders } from '@/test/render';
import { OptionRow } from './changes/OptionRow';
import { OrderSheet } from './orders/OrderSheet';
import { SettingsForm } from './settings/SettingsForm';

const NOW = new Date('2026-11-09T05:40:00Z');

// форми й сторінки рендеряться повністю (Radix, react-hook-form, мок-API): кілька секунд під навантаженням — норма
vi.setConfig({ testTimeout: 20_000 });

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(NOW);
  resetMockStores();
});
afterEach(() => vi.useRealTimers());

const ORDER: OrderDto = {
  id: 'o-35',
  number: 35,
  date: '2026-10-19',
  kind: 'ORDER',
  parentId: null,
  status: 'PUBLISHED',
  title: 'РОЗПОРЯДЖЕННЯ №35',
  subtitle: 'По коледжу про деякі зміни в розкладі занять',
  dateLabel: 'На 19 жовтня 2026 р.',
  publishedAt: '2026-10-16T12:40:00Z',
  publishedBy: 'admin',
  version: 1,
  weekdayLabel: 'ПОНЕДІЛОК',
  parityLabel: 'ЗНАМЕННИК',
  items: [
    { id: 'i1', groupCode: '1ТК1', pairLabel: '1-4', rowType: 'BLOCK', fromDiscipline: 'ЗУ', fromTeacher: 'Ємельяненко', reasonText: null, toDiscipline: null, toTeacher: null, roomText: '204', changeId: null },
    { id: 'i2', groupCode: '1ОП2', pairLabel: '4', rowType: 'ADDITIONAL', fromDiscipline: null, fromTeacher: null, reasonText: null, toDiscipline: 'БЖ', toTeacher: 'Погосян', roomText: '403', changeId: null },
    { id: 'i3', groupCode: '1ТК1', pairLabel: '1', rowType: 'CANCELLATION', fromDiscipline: 'Ін. мова', fromTeacher: 'Крисак Л. (п.)', reasonText: 'Відп.', toDiscipline: null, toTeacher: null, roomText: null, changeId: null },
    { id: 'i4', groupCode: '4КІ1', pairLabel: '2', rowType: 'REPLACEMENT', fromDiscipline: 'СТП', fromTeacher: 'Кренцін', reasonText: 'Зв.зам.', toDiscipline: 'НДКС', toTeacher: 'Непийвода', roomText: '6', changeId: null },
  ],
  roomChanges: [{ id: 'r1', groupCode: '2КІ1', pairLabel: '3', rowType: 'ROOM_CHANGE', fromDiscipline: null, fromTeacher: null, reasonText: null, toDiscipline: null, toTeacher: null, roomText: '304/309', changeId: null }],
  notes: ['Групи 4КІ з 01.09.26р. по 31.10.26 навчаються – дистанційно.'],
  signatoryPosition: 'Заступник директора з НР',
  signatoryName: 'Микола ПОЛІЩУК',
};

describe('аркуш розпорядження', () => {
  it('відтворює формат коледжу: шапка, обʼєднані колонки, усі типи рядків, примітки й підпис', () => {
    renderWithProviders(<OrderSheet order={ORDER} />);
    // між числом і місяцем — нерозривний пробіл (formatLongDate), тому \s
    const sheet = screen.getByRole('article', { name: /Розпорядження №35 на 19\sжовтня 2026/ });
    expect(within(sheet).getByText('ПОНЕДІЛОК - ЗНАМЕННИК')).toBeInTheDocument();
    expect(within(sheet).getByText('На 19 жовтня 2026 р.')).toBeInTheDocument();
    expect(within(sheet).getByRole('columnheader', { name: 'За розкладом' })).toHaveAttribute('colspan', '2');
    expect(within(sheet).getByRole('columnheader', { name: 'По заміні' })).toHaveAttribute('colspan', '2');
    expect(within(sheet).getByText('Пари не буде')).toHaveAttribute('colspan', '2');
    expect(within(sheet).getByText('ДОДАТКОВО')).toHaveAttribute('colspan', '2');
    expect(within(sheet).getByText('1-4')).toBeInTheDocument();
    expect(within(sheet).getByText('304/309')).toBeInTheDocument();
    expect(within(sheet).getByRole('rowheader', { name: /ЗАМІНА\s+АУДИТОРІЙ/ })).toHaveAttribute('colspan', '8');
    expect(within(sheet).getByText('Микола ПОЛІЩУК')).toBeInTheDocument();
    expect(within(sheet).queryByText('ЧЕРНЕТКА')).not.toBeInTheDocument();
  });

  it('чернетка — без номера, з водяним знаком і поясненням, коли рядків ще немає', () => {
    renderWithProviders(<OrderSheet order={{ ...ORDER, status: 'DRAFT', number: null, items: [], roomChanges: [] }} />);
    expect(screen.getByRole('article', { name: /№ ____.*\(чернетка\)/ })).toBeInTheDocument();
    expect(screen.getByText('ЧЕРНЕТКА')).toBeInTheDocument();
    expect(screen.getByText('Підтверджених змін на цю дату ще немає')).toBeInTheDocument();
  });

  it('доповнення називає основне розпорядження', () => {
    renderWithProviders(<OrderSheet order={{ ...ORDER, kind: 'AMENDMENT', number: 37, parentId: 'o-35' }} parentNumber={35} />);
    expect(screen.getByRole('article', { name: /Доповнення №37 до розпорядження №35/ })).toBeInTheDocument();
  });
});

describe('варіант заміни', () => {
  it('показує оцінку, причини, «Чому?» з правилами і обирається кнопкою', async () => {
    const user = userEvent.setup();
    const onPick = vi.fn();
    renderWithProviders(
      <ol>
        <OptionRow
          option={{
            kind: 'REPLACE_TEACHER',
            score: 67,
            title: 'Михальчук',
            reasons: ['Веде цю дисципліну', 'Закриває вікно між 2 і 5 парою', 'Вже має 5 пар цього дня', 'Ще одна'],
            warnings: ['Буде 6 пар цього дня'],
            teacher: { id: 't', shortName: 'Михальчук', displayName: 'Михальчук' },
            discipline: null,
            room: null,
            swapLesson: null,
            date: null,
            pair: null,
          }}
          rank={1}
          best
          busy={false}
          disabled={false}
          onPick={onPick}
        />
      </ol>,
    );
    expect(screen.getByRole('meter', { name: 'Придатність: Михальчук' })).toHaveAttribute('aria-valuenow', '67');
    expect(screen.getByText('ще 2')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /Чому Михальчук/ }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('Буде 6 пар цього дня')).toBeInTheDocument();
    expect(within(dialog).getByText('+50 — веде цю дисципліну')).toBeInTheDocument();
    await user.keyboard('{Escape}');
    await user.click(screen.getByRole('button', { name: 'Обрати: Михальчук' }));
    expect(onPick).toHaveBeenCalledOnce();
  });
});

describe('налаштування', () => {
  const settings: SettingsDto = {
    approvalMode: 'CONFIRM',
    confirmTimeoutMinutes: 15,
    signatoryPosition: 'Заступник директора з НР',
    signatoryName: 'Микола ПОЛІЩУК',
    collegeName: 'Вінницький технічний фаховий коледж',
    hoursPerPair: 2,
    showTeacherFullNames: false,
    activeBellProfileId: null,
  };

  it('перевіряє поля до відправки й показує живий підпис', async () => {
    const user = userEvent.setup();
    renderWithProviders(<SettingsForm settings={settings} guardNavigation={false} numbering={null} ai={null} />);
    const name = screen.getByLabelText(/Імʼя підписанта/);
    await user.clear(name);
    await user.type(name, 'Олена КОВАЛЬ');
    expect(screen.getByText('Олена КОВАЛЬ', { selector: 'span' })).toBeInTheDocument();
    await user.clear(screen.getByLabelText(/Посада підписанта/));
    await user.click(screen.getByRole('button', { name: 'Зберегти' }));
    expect(await screen.findByText('Вкажіть посаду підписанта')).toBeInTheDocument();
  });

  it('зберігає зміни і ховає панель збереження', async () => {
    const user = userEvent.setup();
    renderWithProviders(<SettingsForm settings={settings} guardNavigation={false} numbering={null} ai={null} />);
    await user.click(screen.getByRole('radio', { name: 'Автоматично' }));
    expect(screen.getByText('Є незбережені зміни')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Зберегти' }));
    await waitFor(() => expect(screen.queryByText('Є незбережені зміни')).not.toBeInTheDocument());
  });

  it('помилки сервера стають помилками полів', async () => {
    server.use(
      http.put(apiPath('/admin/settings'), () =>
        problem({ status: 400, detail: 'Перевірте налаштування.', errors: [{ field: 'collegeName', message: 'Така назва вже є' }] }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<SettingsForm settings={settings} guardNavigation={false} numbering={null} ai={null} />);
    await user.type(screen.getByLabelText(/Назва коледжу/), ' (ВТФК)');
    await user.click(screen.getByRole('button', { name: 'Зберегти' }));
    expect(await screen.findByText('Така назва вже є')).toBeInTheDocument();
  });
});

describe('екран «Заміни»', () => {
  // сторінка робить десяток запитів до мок-API: під навантаженням (кілька прогонів поруч) даємо запас часу
  const WAIT = { timeout: 8_000 };

  it('показує відсутніх і пари, обирає варіант клавішею «1», J/K перемикають пару', { timeout: 30_000 }, async () => {
    const user = userEvent.setup();
    renderWithProviders(<ChangesPage />, { route: '/admin/changes?date=2026-11-09' });

    // демо-ранок: три відсутні, пара 3Ф (Крисак) — перша без рішення, її варіанти вже відкриті
    expect(await screen.findByRole('heading', { level: 3, name: 'Крисак Л.В.' }, WAIT)).toBeInTheDocument();
    const firstPick = await screen.findAllByRole('button', { name: /^Обрати:/ }, WAIT);
    expect(firstPick.length).toBeGreaterThan(0);
    expect(screen.getByRole('button', { name: /3Ф/, current: true })).toBeInTheDocument();

    await user.keyboard('1');
    await waitFor(() => {
      const row = screen.getByRole('button', { name: /3Ф/ });
      expect(within(row).getByText('Чекає відповіді')).toBeInTheDocument();
    }, WAIT);

    await user.keyboard('j');
    await waitFor(() => expect(screen.getByRole('button', { name: /4КІ1/, current: true })).toBeInTheDocument(), WAIT);
  });

  it('без мережі — пояснення замість вічних силуетів', { timeout: 20_000 }, async () => {
    onlineManager.setOnline(false);
    try {
      renderWithProviders(<ChangesPage />, { route: '/admin/changes?date=2026-11-09' });
      expect(await screen.findByText('Немає звʼязку з сервером', undefined, WAIT)).toBeInTheDocument();
    } finally {
      onlineManager.setOnline(true);
    }
  });

  it('помилка сервера — повідомлення з повтором, а не порожня сторінка', { timeout: 20_000 }, async () => {
    server.use(http.get(apiPath('/admin/absences'), () => problem({ status: 503 })));
    renderWithProviders(<ChangesPage />, { route: '/admin/changes?date=2026-11-09' });
    expect(await screen.findByRole('button', { name: 'Спробувати ще раз' }, WAIT)).toBeInTheDocument();
  });
});
