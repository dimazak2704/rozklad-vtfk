import { render, waitFor } from '@testing-library/react';
import { useState } from 'react';
import { act } from 'react';
import { toast } from 'sonner';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { Toaster } from './Toaster';
import { TOAST_CLEARANCE_VAR, TOAST_RESERVE_VAR, toastClearanceRef, toastReserve, toastReserveRef } from './toastClearance';

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

afterEach(() => {
  document.documentElement.style.removeProperty(TOAST_CLEARANCE_VAR);
  document.documentElement.style.removeProperty(TOAST_RESERVE_VAR);
  vi.restoreAllMocks();
});

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

/** Список сповіщень, як його малює sonner: fixed над низом вікна, висота передньої картки — у style. */
function toastList(front: string, bottom: number) {
  const list = document.createElement('ol');
  list.setAttribute('data-sonner-toaster', '');
  if (front) list.style.setProperty('--front-toast-height', front);
  vi.spyOn(list, 'getBoundingClientRect').mockReturnValue({ bottom } as DOMRect);
  return list;
}

const reserve = () => document.documentElement.style.getPropertyValue(TOAST_RESERVE_VAR);

describe('запас унизу сторінки під сповіщення', () => {
  it('рахує смугу від низу вікна до верху передньої картки з проміжком', () => {
    // вікно 800 px, список на 16 px над низом, картка 72 px: 16 + 72 + 24
    expect(toastReserve(toastList('72px', 784), 800)).toBe(112);
    // на телефоні під списком ще нижня навігація: 60 + 12 відступу
    expect(toastReserve(toastList('88.4px', 728), 800)).toBe(185);
  });

  it('без сповіщень запасу немає', () => {
    expect(toastReserve(null, 800)).toBe(0);
    expect(toastReserve(toastList('', 784), 800)).toBe(0);
    expect(toastReserve(toastList('0px', 784), 800)).toBe(0);
  });

  it('список за межами вікна не дає відʼємного відступу', () => {
    expect(toastReserve(toastList('72px', 900), 800)).toBe(96);
  });

  it('контейнер сповіщень тримає змінну актуальною і прибирає її за собою', async () => {
    const container = document.createElement('section');
    document.body.append(container);
    const cleanup = toastReserveRef(container);
    expect(reserve()).toBe('');

    const list = toastList('72px', window.innerHeight - 16);
    container.append(list);
    await waitFor(() => expect(reserve()).toBe('112px'));

    // картка виросла (два рядки опису) — запас теж
    list.style.setProperty('--front-toast-height', '96px');
    await waitFor(() => expect(reserve()).toBe('136px'));

    // останнє сповіщення зникло — sonner прибирає список, відступ сторінки теж зникає
    list.remove();
    await waitFor(() => expect(reserve()).toBe(''));

    container.append(toastList('72px', window.innerHeight - 16));
    await waitFor(() => expect(reserve()).toBe('112px'));
    cleanup?.();
    expect(reserve()).toBe('');
    container.remove();
  });

  it('без контейнера нічого не робить', () => {
    expect(toastReserveRef(null)).toBeUndefined();
  });

  it('Toaster виставляє запас, поки видно сповіщення', async () => {
    // jsdom не рахує розміри: картка 72 px, список біля самого низу вікна
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue({ height: 72, bottom: window.innerHeight - 16 } as DOMRect);
    render(<Toaster />);
    act(() => {
      toast.info('Зупиняємо генерацію', { id: 'job-1' });
    });
    await waitFor(() => expect(reserve()).toBe('112px'));
    // сповіщення лишаються оголошуваними для читачів екрана
    expect(document.querySelector('section[aria-live="polite"]')).not.toBeNull();
  });
});
