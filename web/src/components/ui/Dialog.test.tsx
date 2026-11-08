import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, it } from 'vitest';
import { renderWithProviders } from '@/test/render';
import { Button } from './Button';
import { Dialog, DialogContent } from './Dialog';

function QrDialog() {
  const [open, setOpen] = useState(false);
  return (
    <>
      <Button onClick={() => setOpen(true)}>QR-код для дверей</Button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent title="QR-код групи 4КІ1">
          <p>Наведіть камеру телефона.</p>
        </DialogContent>
      </Dialog>
    </>
  );
}

describe('DialogContent', () => {
  it('закривається першим Esc, коли фокус при відкритті стоїть на «Закрити»', async () => {
    const user = userEvent.setup();
    renderWithProviders(<QrDialog />);
    screen.getByRole('button', { name: 'QR-код для дверей' }).focus();
    await user.keyboard('{Enter}');
    expect(await screen.findByRole('dialog', { name: 'QR-код групи 4КІ1' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Закрити' })).toHaveFocus();
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument();

    await user.keyboard('{Escape}');

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  });

  it('закривається першим Esc і тоді, коли на «Закрити» перейшли клавішею Tab', async () => {
    const user = userEvent.setup();
    renderWithProviders(<QrDialog />);
    await user.click(screen.getByRole('button', { name: 'QR-код для дверей' }));
    await screen.findByRole('dialog');
    await user.tab();
    await user.tab();
    expect(screen.getByRole('button', { name: 'Закрити' })).toHaveFocus();

    await user.keyboard('{Escape}');

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  });
});
