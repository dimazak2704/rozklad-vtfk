import { CalendarPlus, FilePlus2, FileText } from 'lucide-react';
import { useNavigate } from 'react-router';
import type { IsoDate } from '@/api/types';
import { useHotkeys } from '@/hooks/useHotkeys';
import { useRegisterCommands } from '@/stores/commands';

interface ShortcutsInput {
  date: IsoDate;
  move: (step: 1 | -1) => void;
  selectNumber: (n: number) => void;
  openNewAbsence: () => void;
  openNewChange: () => void;
}

/** Гарячі клавіші екрана замін (J/K, 1–9, N, C) і ті самі дії в палітрі команд. */
export function useChangesShortcuts({ date, move, selectNumber, openNewAbsence, openNewChange }: ShortcutsInput) {
  const navigate = useNavigate();

  useHotkeys([
    { keys: 'j', handler: () => move(1), description: 'Наступна пара', group: 'Заміни' },
    { keys: 'k', handler: () => move(-1), description: 'Попередня пара', group: 'Заміни' },
    ...[1, 2, 3, 4, 5, 6, 7, 8, 9].map((n) => ({
      keys: String(n),
      handler: () => selectNumber(n),
      description: n === 1 ? 'Обрати варіант за номером (дія — Enter на обраному)' : undefined,
      helpKeys: n === 1 ? '1–9' : undefined,
      group: 'Заміни',
    })),
    {
      keys: 'n',
      handler: openNewAbsence,
      description: 'Нова відсутність',
      group: 'Заміни',
    },
    {
      keys: 'c',
      handler: openNewChange,
      description: 'Нова зміна без відсутності (додаткова пара, аудиторія…)',
      group: 'Заміни',
    },
  ]);

  useRegisterCommands([
    {
      id: 'new-absence',
      label: 'Нова відсутність',
      group: 'Заміни',
      icon: CalendarPlus,
      shortcut: 'n',
      perform: openNewAbsence,
      keywords: ['лікарняний', 'відсутній'],
    },
    {
      id: 'new-change',
      label: 'Нова зміна: додаткова пара, аудиторія, викладач',
      group: 'Заміни',
      icon: FilePlus2,
      shortcut: 'c',
      perform: openNewChange,
      keywords: ['додаткова пара', 'відпрацювання', 'аудиторія', 'вичитано', 'заміна без відсутності'],
    },
    {
      id: 'order-draft',
      label: 'Відкрити чернетку розпорядження',
      group: 'Заміни',
      icon: FileText,
      perform: () => void navigate(`/admin/orders?date=${date}`),
    },
  ]);
}
