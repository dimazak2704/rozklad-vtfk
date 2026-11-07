import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';
import type { IsoDate } from '@/api/types';

interface WorkingDateState {
  /**
   * Робоча дата диспетчера (перемикач «Сьогодні / Завтра / дата» у верхній панелі).
   * null — сьогодні. Живе в межах вкладки: відкрили адмінку завтра — знову «сьогодні».
   */
  date: IsoDate | null;
  setDate: (date: IsoDate | null) => void;
}

export const useWorkingDateStore = create<WorkingDateState>()(
  persist(
    (set) => ({
      date: null,
      setDate: (date) => set({ date }),
    }),
    {
      name: 'rozklad.working-date',
      storage: createJSONStorage(() => sessionStorage),
      version: 1,
    },
  ),
);
