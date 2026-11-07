import { useMemo } from 'react';
import { useHotkeys } from '@/hooks/useHotkeys';
import { useShortcutsStore } from '@/stores/shortcuts';
import { Dialog, DialogContent } from '../ui/Dialog';
import { KbdCombo } from '../ui/Kbd';

/** Довідка «?»: усі клавіатурні скорочення, що діють на цьому екрані. */
export function ShortcutsDialog() {
  const entries = useShortcutsStore((state) => state.entries);
  const open = useShortcutsStore((state) => state.helpOpen);
  const setOpen = useShortcutsStore((state) => state.setHelpOpen);

  useHotkeys([{ keys: '?', handler: () => setOpen(true), description: 'Довідка зі скорочень', group: 'Загальні' }]);

  const groups = useMemo(() => {
    const map = new Map<string, Array<{ keys: string; description: string }>>();
    const seen = new Set<string>();
    for (const entry of Object.values(entries)) {
      const key = `${entry.group}|${entry.keys}`;
      if (seen.has(key)) continue;
      seen.add(key);
      const list = map.get(entry.group) ?? [];
      list.push({ keys: entry.keys, description: entry.description });
      map.set(entry.group, list);
    }
    return [...map.entries()].sort(([a], [b]) => (a === 'Загальні' ? -1 : b === 'Загальні' ? 1 : a.localeCompare(b, 'uk')));
  }, [entries]);

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent title="Клавіатурні скорочення" description="Працюють, коли курсор не в полі вводу." size="md">
        <div className="grid gap-5 sm:grid-cols-2">
          {groups.map(([group, items]) => (
            <section key={group} className="flex flex-col gap-2">
              <h3 className="text-caption font-semibold text-muted">{group}</h3>
              <dl className="flex flex-col gap-1.5">
                {items.map((item) => (
                  <div key={item.keys} className="flex items-center justify-between gap-3">
                    <dt className="text-ui text-primary">{item.description}</dt>
                    <dd>
                      <KbdCombo keys={item.keys} />
                    </dd>
                  </div>
                ))}
              </dl>
            </section>
          ))}
        </div>
      </DialogContent>
    </Dialog>
  );
}
