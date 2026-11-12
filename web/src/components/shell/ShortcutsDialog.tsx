import { Fragment, useMemo } from 'react';
import { useHotkeys } from '@/hooks/useHotkeys';
import { helpGroups, useShortcutsStore } from '@/stores/shortcuts';
import { Dialog, DialogContent } from '../ui/Dialog';
import { KbdCombo } from '../ui/Kbd';

/**
 * Довідка «?»: клавіші, що діють саме на цьому екрані, — спершу розділи сторінки, потім загальні.
 * Кожна клавіша — один раз і з тим значенням, яке вона має тут.
 */
export function ShortcutsDialog() {
  const entries = useShortcutsStore((state) => state.entries);
  const open = useShortcutsStore((state) => state.helpOpen);
  const setOpen = useShortcutsStore((state) => state.setHelpOpen);

  useHotkeys([{ keys: '?', handler: () => setOpen(true), description: 'Довідка зі скорочень', group: 'Загальні' }]);

  const groups = useMemo(() => helpGroups(entries), [entries]);

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent title="Клавіатурні скорочення" description="Літери й «/» не діють, поки курсор у полі вводу." size="md">
        <div className="grid gap-5 sm:grid-cols-2">
          {groups.map(([group, items]) => (
            <section key={group} className="flex flex-col gap-2">
              <h3 className="text-caption font-semibold text-muted">{group}</h3>
              <dl className="flex flex-col gap-1.5">
                {items.map((item) => (
                  <div key={item.keys} className="flex items-center justify-between gap-3">
                    <dt className="text-ui text-primary">{item.description}</dt>
                    <dd className="flex shrink-0 items-center gap-1.5">
                      {item.keys.split(',').map((keys, index) => (
                        <Fragment key={keys}>
                          {index > 0 ? <span className="text-caption text-muted">або</span> : null}
                          <KbdCombo keys={keys} />
                        </Fragment>
                      ))}
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
