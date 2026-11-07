import { lazy, Suspense } from 'react';
import { useHotkeys } from '@/hooks/useHotkeys';
import { useCommandStore } from '@/stores/commands';

const CommandPalette = lazy(() => import('./CommandPalette').then((module) => ({ default: module.CommandPalette })));

/**
 * Скорочення палітри діють завжди, а сам код палітри (cmdk, пошук) вантажиться при першому відкритті —
 * публічна сторінка розкладу не платить за нього на старті.
 */
export function CommandPaletteHost() {
  const open = useCommandStore((state) => state.open);
  const toggle = useCommandStore((state) => state.toggle);
  const setOpen = useCommandStore((state) => state.setOpen);

  useHotkeys([
    { keys: 'mod+k', handler: toggle, description: 'Пошук і команди', group: 'Загальні', allowInInputs: true },
    { keys: '/', handler: () => setOpen(true), description: 'Знайти розклад', group: 'Загальні' },
  ]);

  if (!open) return null;
  return (
    <Suspense fallback={null}>
      <CommandPalette />
    </Suspense>
  );
}
