import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { DropdownMenu } from 'radix-ui';
import { useState } from 'react';
import { describe, expect, it } from 'vitest';
import { renderWithProviders } from '@/test/render';
import { Button } from './Button';
import { Dialog, DialogClose, DialogContent } from './Dialog';

/** Вікно, яке відкривають зі стану (як у застосунку), без DialogTrigger. */
function OpenedFromState({ unmountOnClose = false }: { unmountOnClose?: boolean }) {
  const [open, setOpen] = useState(false);
  const dialog = (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent title="Новий викладач" footer={<DialogClose asChild><Button>Скасувати</Button></DialogClose>}>
        <input aria-label="Прізвище" />
      </DialogContent>
    </Dialog>
  );
  return (
    <>
      <Button onClick={() => setOpen(true)}>Додати викладача</Button>
      <Button>Далі</Button>
      {unmountOnClose ? (open ? dialog : null) : dialog}
    </>
  );
}

function OpenedFromRowMenu() {
  const [open, setOpen] = useState(false);
  return (
    <>
      <DropdownMenu.Root>
        <DropdownMenu.Trigger asChild>
          <Button>Дії: Кренцін</Button>
        </DropdownMenu.Trigger>
        <DropdownMenu.Portal>
          <DropdownMenu.Content>
            <DropdownMenu.Item onSelect={() => setOpen(true)}>Редагувати</DropdownMenu.Item>
          </DropdownMenu.Content>
        </DropdownMenu.Portal>
      </DropdownMenu.Root>
      {open ? (
        <Dialog open onOpenChange={setOpen}>
          <DialogContent title="Редагувати викладача">
            <input aria-label="Прізвище" />
          </DialogContent>
        </Dialog>
      ) : null}
    </>
  );
}

describe('повернення фокуса після закриття вікна', () => {
  it('повертає фокус на кнопку, що відкрила вікно, після Esc', async () => {
    const user = userEvent.setup();
    renderWithProviders(<OpenedFromState />);
    const opener = screen.getByRole('button', { name: 'Додати викладача' });
    await user.click(opener);
    expect(await screen.findByRole('dialog', { name: 'Новий викладач' })).toBeInTheDocument();
    // перший Esc на кнопці «Закрити» ховає її підказку, тож працюємо в полі форми
    await user.click(screen.getByRole('textbox', { name: 'Прізвище' }));

    await user.keyboard('{Escape}');

    await waitFor(() => expect(opener).toHaveFocus());
    await user.tab();
    expect(screen.getByRole('button', { name: 'Далі' })).toHaveFocus();
  });

  it('повертає фокус і тоді, коли вікно зникає разом зі станом', async () => {
    const user = userEvent.setup();
    renderWithProviders(<OpenedFromState unmountOnClose />);
    const opener = screen.getByRole('button', { name: 'Додати викладача' });
    await user.click(opener);
    await user.click(await screen.findByRole('button', { name: 'Скасувати' }));

    await waitFor(() => expect(opener).toHaveFocus());
  });

  it('після вікна з пункту меню повертає фокус на кнопку меню рядка', async () => {
    const user = userEvent.setup();
    renderWithProviders(<OpenedFromRowMenu />);
    const trigger = screen.getByRole('button', { name: 'Дії: Кренцін' });
    trigger.focus();
    await user.keyboard('{Enter}');
    await user.click(await screen.findByRole('menuitem', { name: 'Редагувати' }));
    expect(await screen.findByRole('dialog', { name: 'Редагувати викладача' })).toBeInTheDocument();
    await user.click(screen.getByRole('textbox', { name: 'Прізвище' }));

    await user.keyboard('{Escape}');

    await waitFor(() => expect(trigger).toHaveFocus());
  });

  it('дія прибрала кнопку, з якої відкрили вікно: фокус іде на картку, а не на <body>', async () => {
    const user = userEvent.setup();
    function RemovedOnConfirm() {
      const [open, setOpen] = useState(false);
      const [cancelled, setCancelled] = useState(false);
      return (
        <section aria-label="Рішення щодо пари">
          <p>{cancelled ? 'Рішення скасовано' : 'Чекає відповіді'}</p>
          {cancelled ? null : <Button onClick={() => setOpen(true)}>Скасувати рішення</Button>}
          <Dialog open={open} onOpenChange={setOpen}>
            <DialogContent
              title="Скасувати рішення?"
              footer={
                <DialogClose asChild>
                  <Button onClick={() => setTimeout(() => setCancelled(true), 20)}>Так, скасувати</Button>
                </DialogClose>
              }
            >
              <p>Пару знову треба буде вирішити.</p>
            </DialogContent>
          </Dialog>
        </section>
      );
    }
    renderWithProviders(<RemovedOnConfirm />);
    await user.click(screen.getByRole('button', { name: 'Скасувати рішення' }));
    await user.click(await screen.findByRole('button', { name: 'Так, скасувати' }));

    await waitFor(() => expect(screen.getByText('Рішення скасовано')).toBeInTheDocument());
    await waitFor(() => expect(screen.getByRole('region', { name: 'Рішення щодо пари' })).toHaveFocus());
    expect(document.activeElement).not.toBe(document.body);
  });
});
