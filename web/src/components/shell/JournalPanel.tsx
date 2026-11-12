import { History, Lock, Redo2, Undo2 } from 'lucide-react';
import { useEffect } from 'react';
import { useLocation } from 'react-router';
import { useCurrentUser } from '@/auth/useCurrentUser';
import { useHotkeys, useShortcutHints } from '@/hooks/useHotkeys';
import { cn } from '@/lib/cn';
import { useRegisterCommands } from '@/stores/commands';
import { redoEntry, undoEntry, undoLatest, redoLatest } from '@/lib/undo/actions';
import { latestRedoable, useJournal, type JournalEntry } from '@/lib/undo/journal';
import { submitFocusedForm } from '@/lib/submitForm';
import { Button } from '../ui/Button';
import { Drawer, DrawerContent } from '../ui/Drawer';
import { Spinner } from '../ui/Spinner';
import { notify } from '../ui/toast';

const TIME = new Intl.DateTimeFormat('uk-UA', { hour: '2-digit', minute: '2-digit' });

/** Рядок журналу: час, речення, стан словами і одна тиха дія праворуч. */
function JournalRow({ entry, redoable }: { entry: JournalEntry; redoable: boolean }) {
  const busy = useJournal((state) => state.busy);
  const undone = entry.status === 'undone';
  let state: React.ReactNode = null;
  const notDone = entry.failedOn === 'redo' ? 'Не повернуто' : 'Не скасовано';
  switch (entry.status) {
    case 'undone':
      state =
        entry.failedOn === 'redo' && entry.reason ? (
          <span className="text-muted">
            Скасовано. <span className="text-danger">Не повернуто: {entry.reason}</span>
          </span>
        ) : (
          <span className="text-muted">Скасовано</span>
        );
      break;
    case 'pending':
      state = (
        <span className="inline-flex items-center gap-1.5 text-muted">
          <Spinner size="sm" label="Виконується" /> Виконується…
        </span>
      );
      break;
    case 'failed':
      state = (
        <span className="text-danger">
          {notDone}: {entry.reason}
        </span>
      );
      break;
    case 'stale':
      state = (
        <span className="text-change">
          {notDone}: {entry.reason}
        </span>
      );
      break;
    case 'final':
      state = (
        <span className="inline-flex items-start gap-1.5 text-muted">
          <Lock aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />
          <span>{entry.reason ?? 'Не можна скасувати'}</span>
        </span>
      );
      break;
    case 'done':
      break;
  }

  let action: React.ReactNode = null;
  if (entry.canUndo && (entry.status === 'done' || entry.status === 'failed')) {
    action = (
      <Button data-journal-action variant="ghost" size="sm" disabled={busy} onClick={() => void undoEntry(entry.id)} aria-label={`Скасувати: ${entry.label}`}>
        {entry.status === 'failed' ? 'Ще раз' : 'Скасувати'}
      </Button>
    );
  } else if (undone && redoable) {
    action = (
      <Button data-journal-action variant="ghost" size="sm" disabled={busy} onClick={() => void redoEntry(entry.id)} aria-label={`Повернути: ${entry.label}`}>
        Повернути
      </Button>
    );
  }

  return (
    <li className="flex items-start gap-3 border-b border-separator py-3 last:border-b-0">
      <time dateTime={new Date(entry.at).toISOString()} className="figures w-11 shrink-0 pt-0.5 text-caption text-muted">
        {TIME.format(entry.at)}
      </time>
      <div className="flex min-w-0 flex-1 flex-col gap-0.5">
        <p className={cn('text-ui break-words text-primary', undone && 'text-muted line-through decoration-1')}>{entry.label}</p>
        {state ? <p className="text-caption">{state}</p> : null}
        {entry.note ? <p className="text-caption text-secondary">{entry.note}</p> : null}
      </div>
      {action ? <div className="-me-2 shrink-0">{action}</div> : null}
    </li>
  );
}

/** Панель «Журнал дій»: найновіше — вгорі; що можна — скасувати, що ні — із замком і поясненням. */
export function JournalPanel() {
  const open = useJournal((state) => state.panelOpen);
  const setOpen = useJournal((state) => state.setPanelOpen);
  const entries = useJournal((state) => state.entries);
  const redoStacks = useJournal((state) => state.redoStacks);

  return (
    <Drawer direction="right" open={open} onOpenChange={setOpen}>
      <DrawerContent
        side="right"
        title="Журнал дій"
        aria-describedby={undefined}
        // фокус — у шторку: на першу дію запису («Скасувати» / «Повернути»), інакше на саму шторку
        onOpenAutoFocus={(event) => {
          const content = event.currentTarget as HTMLElement | null;
          const target = content?.querySelector<HTMLElement>('[data-journal-action]:not([disabled])') ?? content;
          target?.focus({ preventScroll: true });
        }}
      >
        {entries.length === 0 ? (
          <p className="pt-2 text-body text-secondary">Тут будуть ваші дії в цій вкладці: що додали, змінили чи скасували.</p>
        ) : (
          <>
            <ul aria-label="Дії в цій вкладці, найновіші першими">
              {entries.map((entry) => (
                <JournalRow key={entry.id} entry={entry} redoable={latestRedoable({ entries, redoStacks }, entry.scope)?.id === entry.id} />
              ))}
            </ul>
            <p className="pt-3 text-caption text-muted">Журнал живе лише в цій вкладці. Повна історія змін — на сервері.</p>
          </>
        )}
      </DrawerContent>
    </Drawer>
  );
}

/**
 * Клавіші панелі диспетчера, спільні для всіх сторінок: скасувати й повернути дію, журнал,
 * зберегти форму. Стежить, яка сторінка зараз відкрита, — Ctrl+Z діє лише на її дії.
 */
export function JournalHost() {
  const { pathname } = useLocation();
  const setPathScope = useJournal((state) => state.setPathScope);
  const togglePanel = () => useJournal.getState().setPanelOpen(!useJournal.getState().panelOpen);

  useEffect(() => {
    setPathScope(pathname);
  }, [pathname, setPathScope]);

  // журнал одного користувача: інший вхід у цій вкладці починає з порожнього
  const userId = useCurrentUser().user?.id ?? null;
  useEffect(() => {
    if (userId) useJournal.getState().setOwner(userId);
  }, [userId]);

  useHotkeys([
    { keys: 'mod+z', handler: () => void undoLatest(), description: 'Скасувати останню дію на сторінці', group: 'Дії' },
    { keys: 'mod+shift+z', handler: () => void redoLatest(), description: 'Повернути скасоване', helpKeys: 'mod+shift+z,mod+y', group: 'Дії' },
    { keys: 'mod+y', handler: () => void redoLatest() },
    { keys: 'mod+shift+h', handler: togglePanel, description: 'Журнал дій', group: 'Дії', allowInModal: true },
    {
      keys: 'mod+s',
      // у панелі Ctrl+S ніколи не відкриває браузерне «Зберегти сторінку як…»
      handler: () => {
        const outcome = submitFocusedForm('save');
        if (outcome === 'nothing' || outcome === 'none') notify.info('Немає що зберігати', { id: 'nothing-to-save', duration: 2000 });
        return true;
      },
      description: 'Зберегти форму',
      group: 'Форми й вікна',
      allowInInputs: true,
    },
    {
      keys: 'mod+enter',
      handler: () => submitFocusedForm('confirm') !== 'none',
      description: 'Зберегти з багаторядкового поля чи підтвердити',
      group: 'Форми й вікна',
      allowInInputs: true,
    },
  ]);
  useRegisterCommands([
    { id: 'journal', label: 'Журнал дій', group: 'Дії', icon: History, shortcut: 'mod+shift+h', perform: () => useJournal.getState().setPanelOpen(true), keywords: ['історія', 'скасувати'] },
    { id: 'undo', label: 'Скасувати останню дію', group: 'Дії', icon: Undo2, shortcut: 'mod+z', perform: () => void undoLatest() },
    { id: 'redo', label: 'Повернути скасоване', group: 'Дії', icon: Redo2, shortcut: 'mod+shift+z', perform: () => void redoLatest() },
  ]);
  useShortcutHints([
    { keys: 'enter', description: 'Зберегти форму з поля вводу', group: 'Форми й вікна' },
    { keys: 'escape', description: 'Закрити вікно чи меню; у пошуку — очистити, ще раз — вийти з поля', group: 'Форми й вікна' },
  ]);

  return <JournalPanel />;
}
