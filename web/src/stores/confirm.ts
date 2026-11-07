import type { ReactNode } from 'react';
import { create } from 'zustand';

export interface ConfirmOptions {
  title: ReactNode;
  description?: ReactNode;
  /**
   * Назва обʼєкта, який зачіпає дія («розпорядження №35», «викладач Кренцін М.Д.»).
   * Для небезпечних дій показується окремо, щоб людина бачила, що саме видаляє.
   */
  objectName?: string;
  confirmLabel?: string;
  cancelLabel?: string;
  tone?: 'default' | 'danger';
  /** Асинхронна дія: діалог тримає кнопку в стані завантаження, доки вона не завершиться. */
  onConfirm?: () => unknown;
}

interface ConfirmRequest extends ConfirmOptions {
  id: number;
  resolve: (confirmed: boolean) => void;
}

interface ConfirmState {
  current: ConfirmRequest | null;
  open: (options: ConfirmOptions) => Promise<boolean>;
  settle: (confirmed: boolean) => void;
}

let nextId = 1;

export const useConfirmStore = create<ConfirmState>()((set, get) => ({
  current: null,
  open: (options) =>
    new Promise<boolean>((resolve) => {
      // новий запит закриває попередній як «скасовано»
      get().current?.resolve(false);
      nextId += 1;
      set({ current: { ...options, id: nextId, resolve } });
    }),
  settle: (confirmed) => {
    const current = get().current;
    if (!current) return;
    current.resolve(confirmed);
    set({ current: null });
  },
}));

/**
 * Імперативне підтвердження: `if (await confirm({ title: 'Скасувати заміну?', tone: 'danger' })) …`.
 * Потребує <ConfirmDialogHost/> у корені (є в RootLayout).
 */
export function confirm(options: ConfirmOptions): Promise<boolean> {
  return useConfirmStore.getState().open(options);
}
