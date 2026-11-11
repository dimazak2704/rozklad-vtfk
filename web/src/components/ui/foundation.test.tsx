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

const unavailable = new ApiError({ status: 503 });

describe('PageErrorScope', () => {
  it('показує одну помилку на сторінку, а блоки всередині — тихим рядком без власних кнопок', () => {
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
    expect(screen.getAllByText('Не вдалося завантажити.')).toHaveLength(2);
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

  it('в адмінці «назад» ховається там, де видно бічну панель', () => {
    renderWithProviders(<PageHeader title="Навантаження" />, { route: '/admin/load' });
    const back = screen.getByRole('link', { name: 'Назад: Сьогодні' });
    expect(back.parentElement).toHaveClass('lg:hidden');
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
});
