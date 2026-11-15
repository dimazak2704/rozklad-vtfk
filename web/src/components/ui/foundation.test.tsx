import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeAll, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/api/errors';
import { renderWithProviders } from '@/test/render';
import { DataTable, type DataTableColumn } from './DataTable';
import { Drawer, DrawerContent } from './Drawer';
import { ErrorState } from './ErrorState';
import { PageHeader } from './PageHeader';
import { PageErrorScope } from './PageErrors';
import { RadioGroup } from './RadioGroup';

const unavailable = new ApiError({ status: 503 });

describe('PageErrorScope', () => {
  it('показує одну помилку на сторінку, а блоки всередині її не повторюють', () => {
    const failed = { isError: true, error: unavailable, isFetching: false, refetch: vi.fn() };
    renderWithProviders(
      <PageErrorScope queries={[failed, { ...failed, refetch: vi.fn() }, { isError: false, error: null, refetch: vi.fn() }]}>
        <ErrorState error={unavailable} onRetry={() => {}} />
        <ErrorState error={unavailable} onRetry={() => {}} />
      </PageErrorScope>,
    );
    expect(screen.getAllByRole('alert')).toHaveLength(1);
    expect(screen.getByRole('alert')).toHaveTextContent('Сервіс тимчасово недоступний');
    expect(screen.getAllByRole('button', { name: /Спробувати ще раз/ })).toHaveLength(1);
    expect(screen.queryByText(/Не вдалося завантажити/)).not.toBeInTheDocument();
  });

  it('рядок помилки стоїть під заголовком сторінки, а «‹ Розклад» лишається', () => {
    const failed = { isError: true, error: unavailable, isFetching: false, refetch: vi.fn() };
    renderWithProviders(
      <PageErrorScope queries={[failed]} title="Розклад 4КІ1 не завантажився">
        <PageHeader title="4КІ1" />
        <p>Вміст</p>
      </PageErrorScope>,
      { route: '/g/4КІ1' },
    );
    const header = screen.getByRole('banner');
    expect(within(header).getByRole('link', { name: 'Назад: Розклад' })).toBeInTheDocument();
    expect(within(header).getByRole('alert')).toHaveTextContent('Розклад 4КІ1 не завантажився.');
    expect(screen.getAllByRole('alert')).toHaveLength(1);
  });

  it('код запиту — лише за «Подробицями», з кнопкою «Копіювати»', async () => {
    const user = userEvent.setup();
    const error = new ApiError({ status: 503, requestId: '9f0aee30-1111-2222-3333-444455556666' });
    renderWithProviders(<ErrorState error={error} onRetry={() => {}} />);
    expect(screen.queryByText(/9f0aee30/)).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Подробиці' }));
    expect(screen.getByText('9f0aee30-1111-2222-3333-444455556666')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Копіювати код запиту' })).toBeInTheDocument();
  });

  it('одна кнопка повторює всі невдалі запити', async () => {
    const user = userEvent.setup();
    const first = vi.fn();
    const second = vi.fn();
    const healthy = vi.fn();
    renderWithProviders(
      <PageErrorScope
        queries={[
          { isError: true, error: unavailable, refetch: first },
          { isError: true, error: unavailable, refetch: second },
          { isError: false, error: null, refetch: healthy },
        ]}
      >
        <p>Вміст</p>
      </PageErrorScope>,
    );
    await user.click(screen.getByRole('button', { name: /Спробувати ще раз/ }));
    expect(first).toHaveBeenCalledOnce();
    expect(second).toHaveBeenCalledOnce();
    expect(healthy).not.toHaveBeenCalled();
  });

  it('без помилок нічого зайвого не малює', () => {
    renderWithProviders(
      <PageErrorScope queries={[{ isError: false, error: null, refetch: vi.fn() }, undefined]}>
        <ErrorState error={unavailable} onRetry={() => {}} />
      </PageErrorScope>,
    );
    // поза помилкою сторінки блок поводиться як звичайно — з власною кнопкою
    expect(screen.getAllByRole('alert')).toHaveLength(1);
    expect(screen.getByRole('button', { name: /Спробувати ще раз/ })).toBeInTheDocument();
  });
});

describe('PageHeader', () => {
  it('на вкладеній сторінці веде на рівень вище за реєстром маршрутів', () => {
    renderWithProviders(<PageHeader title="4КІ1" />, { route: '/g/4КІ1' });
    expect(screen.getByRole('link', { name: 'Назад: Розклад' })).toHaveAttribute('href', '/');
    expect(screen.getByRole('heading', { level: 1, name: '4КІ1' })).toBeInTheDocument();
  });

  it('у кабінеті — на «Мій розклад», на головній — без «назад»', () => {
    const { unmount } = renderWithProviders(<PageHeader title="Мої відсутності" />, { route: '/me/absences' });
    expect(screen.getByRole('link', { name: 'Назад: Мій розклад' })).toHaveAttribute('href', '/me');
    unmount();
    renderWithProviders(<PageHeader title="Розклад занять" />, { route: '/' });
    expect(screen.queryByRole('link', { name: /Назад/ })).not.toBeInTheDocument();
  });

  it('розділ панелі диспетчера — верхній рівень: без «‹ Сьогодні» (до інших розділів веде меню)', () => {
    renderWithProviders(<PageHeader title="Навантаження" />, { route: '/admin/load' });
    expect(screen.queryByRole('link', { name: /^Назад/ })).not.toBeInTheDocument();
  });

  it('заголовок сторінки — роль headline (30 px), а не title розділу', () => {
    renderWithProviders(<PageHeader title="Навантаження" />, { route: '/admin/load' });
    expect(screen.getByRole('heading', { level: 1 })).toHaveClass('sm:text-headline');
  });

  it('головна дія і «⋯» — одна група, керування — окремо від неї', () => {
    renderWithProviders(
      <PageHeader
        title="Базовий розклад"
        back={false}
        controls={<button type="button">Версія</button>}
        primaryAction={<button type="button">Опублікувати</button>}
        more={[{ label: 'Порівняти', onSelect: () => {} }]}
      />,
    );
    const primary = screen.getByRole('button', { name: 'Опублікувати' });
    const more = screen.getByRole('button', { name: 'Ще дії' });
    const control = screen.getByRole('button', { name: 'Версія' });
    expect(primary.parentElement).toBe(more.parentElement);
    expect(primary.parentElement).toHaveClass('shrink-0');
    expect(control.parentElement).not.toBe(primary.parentElement);
  });

  it('другорядні дії — в одному меню «⋯»', async () => {
    const user = userEvent.setup();
    const share = vi.fn();
    renderWithProviders(<PageHeader title="4КІ1" back={false} more={[{ label: 'Поділитися', onSelect: share }]} />);
    await user.click(screen.getByRole('button', { name: 'Ще дії' }));
    await user.click(await screen.findByRole('menuitem', { name: 'Поділитися' }));
    expect(share).toHaveBeenCalledOnce();
  });
});

describe('DataTable на телефоні', () => {
  interface Row {
    id: string;
    name: string;
    hours: number;
  }
  const columns: DataTableColumn<Row>[] = [
    { id: 'name', header: 'Дисципліна', cell: (row) => row.name, rowHeader: true },
    { id: 'hours', header: 'Год/тиж', cell: (row) => row.hours, numeric: true },
  ];

  it('підписує значення назвою колонки, а заголовок рядка лишає без підпису', () => {
    renderWithProviders(
      <DataTable caption="Навантаження" columns={columns} rows={[{ id: '1', name: 'КМ', hours: 4 }]} getRowId={(row) => row.id} />,
    );
    const row = screen.getAllByRole('row')[1]!;
    expect(within(row).getByRole('rowheader')).not.toHaveAttribute('data-label');
    expect(within(row).getByRole('cell')).toHaveAttribute('data-label', 'Год/тиж');
  });

  it('з власним рядком — назва й один рядок підпису без «Підпис:», клік відкриває рядок', async () => {
    const user = userEvent.setup();
    const open = vi.fn();
    renderWithProviders(
      <DataTable
        caption="Навантаження"
        columns={columns}
        rows={[{ id: '1', name: 'КМ', hours: 4 }]}
        getRowId={(row) => row.id}
        onRowClick={open}
        mobileRow={(row) => ({ title: `1ВЕ · ${row.name}`, caption: `${row.hours} год/тиж` })}
      />,
    );
    const list = screen.getByRole('list', { name: 'Навантаження' });
    expect(within(list).getByText('1ВЕ · КМ')).toBeInTheDocument();
    expect(within(list).getByText('4 год/тиж')).toBeInTheDocument();
    await user.click(within(list).getByRole('button', { name: /1ВЕ · КМ/ }));
    expect(open).toHaveBeenCalledWith({ id: '1', name: 'КМ', hours: 4 });
  });

  it('без стосу підписів немає (сітки, де важливі стовпці)', () => {
    renderWithProviders(
      <DataTable
        caption="Навантаження"
        columns={columns}
        rows={[{ id: '1', name: 'КМ', hours: 4 }]}
        getRowId={(row) => row.id}
        stackOnMobile={false}
      />,
    );
    expect(screen.getByRole('cell')).not.toHaveAttribute('data-label');
  });
});

describe('Drawer', () => {
  beforeAll(() => {
    // jsdom не вміє захоплювати вказівник, а vaul викликає це на pointerdown усередині шторки
    Element.prototype.setPointerCapture ??= () => {};
    Element.prototype.releasePointerCapture ??= () => {};
  });

  it('завжди має видиму кнопку «Закрити»', async () => {
    const user = userEvent.setup();
    const onOpenChange = vi.fn();
    renderWithProviders(
      <Drawer open onOpenChange={onOpenChange}>
        <DrawerContent title="Варіанти рішення">
          <p>Вміст</p>
        </DrawerContent>
      </Drawer>,
    );
    await user.click(await screen.findByRole('button', { name: 'Закрити' }));
    expect(onOpenChange).toHaveBeenCalledWith(false);
  });

  it('на телефоні бічна шторка виїжджає знизу з «ручкою»', async () => {
    const original = window.matchMedia;
    window.matchMedia = ((query: string) => ({ ...original(query), matches: query.includes('max-width: 767') }));
    try {
      renderWithProviders(
        <Drawer direction="right" open>
          <DrawerContent side="right" title="Варіанти">
            <p>Вміст</p>
          </DrawerContent>
        </Drawer>,
      );
      const sheet = await screen.findByRole('dialog', { name: 'Варіанти' });
      expect(sheet).toHaveAttribute('data-side', 'bottom');
    } finally {
      window.matchMedia = original;
    }
  });
});

describe('RadioGroup', () => {
  it('увесь рядок — підпис: натискання на текст вибирає варіант', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderWithProviders(
      <RadioGroup
        legend="Причина"
        value="sick"
        onValueChange={onChange}
        options={[
          { value: 'sick', label: 'Лікарняний' },
          { value: 'trip', label: 'Відрядження' },
        ]}
      />,
    );
    expect(screen.getByText('Відрядження').closest('label')).toHaveClass('min-h-11');
    await user.click(screen.getByText('Відрядження'));
    expect(onChange).toHaveBeenCalledWith('trip');
    expect(screen.getByRole('radio', { name: 'Відрядження' })).toBeInTheDocument();
  });
});
