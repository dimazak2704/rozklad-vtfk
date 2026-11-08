import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { onlineManager } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { useState } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ChangeOption } from '@/api/admin-daily';
import type { AbsenceDto, LessonOptionsDto, OrderDto, SettingsDto, TeacherRef } from '@/api/types';
import {
  DAY_PLAN,
  FIXTURE_LESSON,
  FIXTURE_TEACHERS,
  OPTIONS_EMPTY_POOL,
  OPTIONS_EQUAL,
  OPTIONS_PAST,
  OPTIONS_RECOMMENDED,
  TEACHER_AVAILABILITY,
} from '@/mocks/fixtures/lesson-options';
import { apiPath, problem } from '@/mocks/kit';
import { server } from '@/mocks/server';
import { resetMockStores } from '@/mocks/store';
import { useNavigate } from 'react-router';
import ChangesPage from '@/pages/admin/ChangesPage';
import DashboardPage from '@/pages/admin/DashboardPage';
import OrdersPage from '@/pages/admin/OrdersPage';
import { renderWithProviders } from '@/test/render';
import { OptionsPanel } from './changes/OptionsPanel';
import { optionKey, type LessonEntry, type PlanChoice } from './model';
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
  imported: false,
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

describe('варіанти рішення (рекомендатор v2)', () => {
  const absence: AbsenceDto = {
    id: 'a-krysak',
    teacher: FIXTURE_TEACHERS.krysak,
    dateFrom: '2026-11-09',
    dateTo: '2026-11-11',
    pairs: null,
    reasonCategory: 'SICK_LEAVE',
    comment: null,
    source: 'BOT',
    status: 'ACTIVE',
    createdBy: 'telegram-bot',
    createdAt: '2026-11-09T04:00:00Z',
    version: 0,
  };
  const entry: LessonEntry = {
    absence,
    item: { date: '2026-11-09', lesson: FIXTURE_LESSON, decision: null, asSubstitute: false },
    state: 'uncovered',
    change: null,
  };

  function serve(options: LessonOptionsDto) {
    server.use(
      http.get(apiPath('/admin/lessons/:id/options'), () => HttpResponse.json(options)),
      http.get(apiPath('/admin/lessons/:id/teacher-availability'), () => HttpResponse.json(TEACHER_AVAILABILITY)),
      http.get(apiPath('/admin/absences/:id/plan'), () => HttpResponse.json(DAY_PLAN)),
    );
  }

  /** Стан вибору, як у ChangesPage: панель лише повідомляє, що обрано. */
  function Harness(props: {
    onAct: (option: ChangeOption) => void;
    onAssignManual?: (teacher: TeacherRef) => Promise<boolean>;
    onApplyPlan?: (choices: PlanChoice[]) => Promise<boolean>;
    approvalMode?: string;
    planCount?: number;
  }) {
    const [selectedKey, setSelectedKey] = useState<string | null>(null);
    const [expanded, setExpanded] = useState(false);
    return (
      <OptionsPanel
        entry={entry}
        approvalMode={props.approvalMode ?? 'CONFIRM'}
        reasonCode="ABSENT"
        reasonText=""
        onReasonCodeChange={() => undefined}
        onReasonTextChange={() => undefined}
        selectedKey={selectedKey}
        onSelect={(option) => setSelectedKey(optionKey(option))}
        expanded={expanded}
        onExpandedChange={setExpanded}
        pickingKey={null}
        onAct={props.onAct}
        onAssignManual={props.onAssignManual ?? (() => Promise.resolve(true))}
        planCount={props.planCount ?? 1}
        onApplyPlan={props.onApplyPlan ?? (() => Promise.resolve(true))}
        decisionBusy={null}
        onResend={() => undefined}
        onConfirm={() => undefined}
        onCancelDecision={() => undefined}
      />
    );
  }

  const rows = () => within(screen.getByRole('list', { name: 'Варіанти' })).getAllByRole('button', { pressed: false }).concat(
    within(screen.getByRole('list', { name: 'Варіанти' })).queryAllByRole('button', { pressed: true }),
  );

  it('один список у порядку сервера: три рядки, «Ще варіанти», без балів і номерів', async () => {
    serve(OPTIONS_RECOMMENDED);
    const user = userEvent.setup();
    const { container } = renderWithProviders(<Harness onAct={vi.fn()} />);
    const list = await screen.findByRole('list', { name: 'Варіанти' });
    const titles = within(list).getAllByRole('button', { pressed: false }).map((b) => b.textContent);
    expect(titles).toHaveLength(3);
    expect(titles[0]).toContain('НДКС — Непийвода');
    expect(titles[1]).toContain('Обʼєднати підгрупи — Можаровська веде обидві');
    // жодних вкладок, шкал і «N зі 100»
    expect(screen.queryByRole('tab')).not.toBeInTheDocument();
    expect(screen.queryByRole('meter')).not.toBeInTheDocument();
    expect(container.textContent).not.toMatch(/зі 100|\b(100|90|80)\b/);

    const more = screen.getByRole('button', { name: 'Ще варіанти (2)' });
    expect(more).toHaveAttribute('aria-expanded', 'false');
    await user.click(more);
    expect(rows()).toHaveLength(5);
    expect(screen.getByRole('button', { name: 'Менше варіантів' })).toHaveAttribute('aria-expanded', 'true');
  });

  it('«Рекомендовано» лише на першому й лише коли сервер так позначив; відмови й аудиторія', async () => {
    serve(OPTIONS_RECOMMENDED);
    renderWithProviders(<Harness onAct={vi.fn()} />);
    const list = await screen.findByRole('list', { name: 'Варіанти' });
    expect(within(list).getAllByText('Рекомендовано')).toHaveLength(1);
    expect(within(list).getAllByRole('button', { pressed: false })[0]).toHaveTextContent('Рекомендовано');
    expect(screen.queryByText('Рівноцінні варіанти — оберіть самі')).not.toBeInTheDocument();
    expect(screen.getByText('Відмовились: Ганза (14:05)')).toBeInTheDocument();
    // аудиторія, куди прийде група: своя аудиторія замінника й аудиторія іншої підгрупи
    expect(within(list).getByText('ауд. 6')).toBeInTheDocument();
    expect(within(list).getByText('ауд. 12')).toBeInTheDocument();
    // «⚠» видно словом і кольором; у рядку не більше трьох чипів
    const caution = within(list).getAllByRole('listitem').find((li) => li.textContent.includes('Прогр. — Стець'));
    expect(caution).toBeDefined();
    expect(within(caution as HTMLElement).getByText(/Вже має пару з цією групою/)).toBeInTheDocument();
    expect(within(caution as HTMLElement).getAllByText('Увага:')).toHaveLength(2);
  });

  it('без рекомендації — «Рівноцінні варіанти»; минула пара — свій заголовок; порожній пул — пояснення', async () => {
    serve(OPTIONS_EQUAL);
    const { unmount } = renderWithProviders(<Harness onAct={vi.fn()} />);
    expect(await screen.findByText('Рівноцінні варіанти — оберіть самі')).toBeInTheDocument();
    expect(screen.queryByText('Рекомендовано')).not.toBeInTheDocument();
    unmount();

    serve(OPTIONS_PAST);
    const past = renderWithProviders(<Harness onAct={vi.fn()} />);
    expect(await screen.findByText('Заняття вже минуло — позначте, що було насправді.')).toBeInTheDocument();
    past.unmount();

    serve(OPTIONS_EMPTY_POOL);
    renderWithProviders(<Harness onAct={vi.fn()} />);
    expect(await screen.findByText('Вільних викладачів групи на цю пару немає. Можна переставити або скасувати пару.')).toBeInTheDocument();
  });

  it('вибір ≠ дія: клік лише обирає, дія — кнопка в обраному рядку або Enter', async () => {
    serve(OPTIONS_RECOMMENDED);
    const user = userEvent.setup();
    const onAct = vi.fn();
    renderWithProviders(<Harness onAct={onAct} />);
    const list = await screen.findByRole('list', { name: 'Варіанти' });
    expect(within(list).queryByRole('button', { name: 'Надіслати прохання' })).not.toBeInTheDocument();

    const merge = within(list).getByRole('button', { name: /Обʼєднати підгрупи — Можаровська веде обидві/, pressed: false });
    await user.click(merge);
    expect(onAct).not.toHaveBeenCalled();
    expect(merge).toHaveAttribute('aria-pressed', 'true');
    const send = within(list).getByRole('button', { name: 'Надіслати прохання' });
    await user.click(send);
    expect(onAct).toHaveBeenCalledOnce();
    expect(onAct.mock.calls[0]?.[0]).toMatchObject({ kind: 'MERGE_SUBGROUPS' });

    // Enter на обраному рядку — та сама дія; на необраному — лише вибір
    const first = within(list).getByRole('button', { name: /НДКС — Непийвода/, pressed: false });
    first.focus();
    await user.keyboard('{Enter}');
    expect(first).toHaveAttribute('aria-pressed', 'true');
    expect(onAct).toHaveBeenCalledOnce();
    await user.keyboard('{Enter}');
    expect(onAct).toHaveBeenCalledTimes(2);
    expect(onAct.mock.calls[1]?.[0]).toMatchObject({ kind: 'REPLACE_DISCIPLINE', teacher: { shortName: 'Непийвода' } });
  });

  it('напис дії за режимом: не CONFIRM — «Призначити», скасування — «Застосувати»', async () => {
    serve(OPTIONS_RECOMMENDED);
    const user = userEvent.setup();
    renderWithProviders(<Harness onAct={vi.fn()} approvalMode="NOTIFY" />);
    const list = await screen.findByRole('list', { name: 'Варіанти' });
    await user.click(within(list).getByRole('button', { name: /НДКС — Непийвода/, pressed: false }));
    expect(within(list).getByRole('button', { name: 'Призначити' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Ще варіанти (2)' }));
    await user.click(within(list).getByRole('button', { name: /Пари не буде/, pressed: false }));
    expect(within(list).getByRole('button', { name: 'Застосувати' })).toBeInTheDocument();
  });

  it('«Інший викладач…»: стани словами, обрати можна лише вільного, примітка про виняток', async () => {
    serve(OPTIONS_RECOMMENDED);
    const user = userEvent.setup();
    const onAssignManual = vi.fn(() => Promise.resolve(true));
    renderWithProviders(<Harness onAct={vi.fn()} onAssignManual={onAssignManual} />);
    await user.click(await screen.findByRole('button', { name: 'Інший викладач…' }));
    const dialog = await screen.findByRole('dialog', { name: 'Інший викладач на 3 пару' });
    expect(within(dialog).getByText('Ручне призначення — лише як виняток.')).toBeInTheDocument();
    expect(within(dialog).getByPlaceholderText('Прізвище')).toBeInTheDocument();
    expect(within(dialog).getByText('Зайнятий(а): 3 пара, 4КІ2')).toBeInTheDocument();
    expect(within(dialog).getByText('Відмовився(лась) о 14:05')).toBeInTheDocument();
    expect(within(dialog).getByText('Недоступний(а) в цей час')).toBeInTheDocument();
    expect(within(dialog).getByRole('radio', { name: 'Стець' })).toBeDisabled();
    const send = within(dialog).getByRole('button', { name: 'Надіслати прохання' });
    expect(send).toBeDisabled();
    await user.click(within(dialog).getByRole('radio', { name: 'Прокопчук' }));
    await user.click(send);
    expect(onAssignManual).toHaveBeenCalledWith(expect.objectContaining({ shortName: 'Прокопчук' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  });

  it('план дня: пропозиція не надсилається сама, підсумок прізвищами, «Надіслати всі (n)»', async () => {
    serve(OPTIONS_RECOMMENDED);
    const user = userEvent.setup();
    const onApplyPlan = vi.fn((_choices: PlanChoice[]) => Promise.resolve(true));
    renderWithProviders(<Harness onAct={vi.fn()} onApplyPlan={onApplyPlan} planCount={2} />);
    await user.click(await screen.findByRole('button', { name: 'Запропонувати план дня' }));
    const dialog = await screen.findByRole('dialog', { name: 'План дня: Крисак Л.' });
    expect(await within(dialog).findByText('2 прохання в Telegram: Непийвода, Балунь')).toBeInTheDocument();
    expect(onApplyPlan).not.toHaveBeenCalled();

    // другу пару лишаємо без рішення — у підсумку й на кнопці одна
    await user.click(within(dialog).getByRole('combobox', { name: 'Рішення для 4 пари, 1М' }));
    await user.click(await screen.findByRole('option', { name: 'Не вирішувати зараз' }));
    expect(within(dialog).getByText('1 прохання в Telegram: Непийвода')).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Надіслати всі (1)' }));
    expect(onApplyPlan).toHaveBeenCalledOnce();
    expect(onApplyPlan.mock.calls[0]?.[0].map((c) => c.option.title)).toEqual(['НДКС — Непийвода']);
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
    version: 0,
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

  it('показує відсутніх і пари; «1» лише обирає варіант, Enter виконує; J/K перемикають пару', { timeout: 30_000 }, async () => {
    const user = userEvent.setup();
    renderWithProviders(<ChangesPage />, { route: '/admin/changes?date=2026-11-09' });

    // демо-ранок: три відсутні, пара 3Ф (Крисак) — перша без рішення, її варіанти вже відкриті
    expect(await screen.findByRole('heading', { level: 3, name: 'Крисак Л.В.' }, WAIT)).toBeInTheDocument();
    const list = await screen.findByRole('list', { name: 'Варіанти' }, WAIT);
    expect(screen.getByRole('button', { name: /3Ф/, current: true })).toBeInTheDocument();

    // «1» лише обирає перший варіант і ставить на нього фокус — запиту ще немає
    await user.keyboard('1');
    const chosen = await within(list).findByRole('button', { pressed: true }, WAIT);
    await waitFor(() => expect(chosen).toHaveFocus(), WAIT);
    expect(within(screen.getByRole('button', { name: /3Ф/ })).queryByText('Чекає відповіді')).not.toBeInTheDocument();
    expect(within(list).getByRole('button', { name: 'Надіслати прохання' })).toBeInTheDocument();

    await user.keyboard('{Enter}');
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

  it('панель без мережі на новій даті — «немає звʼязку», а не дані попередньої дати', { timeout: 20_000 }, async () => {
    function GoToNextDay() {
      const navigate = useNavigate();
      return (
        <button type="button" onClick={() => void navigate('/admin?date=2026-11-10')}>
          наступний день
        </button>
      );
    }
    renderWithProviders(
      <>
        <GoToNextDay />
        <DashboardPage />
      </>,
      { route: '/admin?date=2026-11-09' },
    );
    await waitFor(() => expect(screen.queryByText('Завантажуємо сповіщення…')).not.toBeInTheDocument(), WAIT);
    expect(screen.getByText('Оновлюється наживо')).toBeInTheDocument();

    onlineManager.setOnline(false);
    try {
      await userEvent.click(screen.getByRole('button', { name: 'наступний день' }));
      expect((await screen.findAllByText('Немає звʼязку з сервером', undefined, WAIT)).length).toBeGreaterThan(0);
      expect(screen.queryByText('Оновлюється наживо')).not.toBeInTheDocument();
      expect(screen.queryByText('Маршрут ранку')).not.toBeInTheDocument();
    } finally {
      onlineManager.setOnline(true);
    }
  });

  it('розпорядження без мережі — пояснення замість вічного силуету аркуша', { timeout: 20_000 }, async () => {
    onlineManager.setOnline(false);
    try {
      renderWithProviders(<OrdersPage />, { route: '/admin/orders?date=2026-11-10' });
      expect(await screen.findByText('Немає звʼязку з сервером', undefined, WAIT)).toBeInTheDocument();
      expect(screen.queryByText('Складаємо розпорядження…')).not.toBeInTheDocument();
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
