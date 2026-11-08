import { render, waitFor } from '@testing-library/react';
import { useState } from 'react';
import { act } from 'react';
import { toast } from 'sonner';
import { afterEach, describe, expect, it } from 'vitest';
import { Toaster } from './Toaster';
import { TOAST_CLEARANCE_VAR, toastClearanceRef } from './toastClearance';

const clearance = () => document.documentElement.style.getPropertyValue(TOAST_CLEARANCE_VAR);

/** jsdom не рахує розміри: висоту панелі задаємо руками. */
function withHeight(height: number) {
  return (element: HTMLDivElement | null) => {
    if (element) Object.defineProperty(element, 'offsetHeight', { configurable: true, value: height });
    return toastClearanceRef(element);
  };
}

function SaveBar({ height }: { height: number }) {
  const [dirty, setDirty] = useState(true);
  return (
    <>
      <button type="button" onClick={() => setDirty((d) => !d)}>
        перемкнути
      </button>
      {dirty ? <div ref={withHeight(height)}>Є незбережені зміни</div> : null}
    </>
  );
}

afterEach(() => document.documentElement.style.removeProperty(TOAST_CLEARANCE_VAR));

describe('сповіщення над липкою панеллю збереження', () => {
  it('поки панель на сторінці, сповіщення піднімаються над нею; без панелі — на звичайному місці', () => {
    const { getByRole } = render(<SaveBar height={64} />);
    expect(clearance()).toBe('72px');

    act(() => getByRole('button').click());
    expect(clearance()).toBe('');
  });

  it('прихована панель (висота 0) місця не займає', () => {
    render(<SaveBar height={0} />);
    expect(clearance()).toBe('');
  });

  it('Toaster рахує нижній відступ від змінної панелі', async () => {
    render(<Toaster />);
    act(() => {
      toast.error('Не вдалося зберегти налаштування');
    });
    const toaster = await waitFor(() => {
      const element = document.querySelector<HTMLElement>('[data-sonner-toaster]');
      expect(element).not.toBeNull();
      return element as HTMLElement;
    });
    expect(toaster.style.getPropertyValue('--offset-bottom')).toBe(`calc(var(${TOAST_CLEARANCE_VAR}, 0px) + 16px)`);
    expect(toaster.style.getPropertyValue('--mobile-offset-bottom')).toContain(`var(${TOAST_CLEARANCE_VAR}, 0px)`);
  });
});
