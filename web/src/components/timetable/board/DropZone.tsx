import { useDroppable } from '@dnd-kit/core';
import { Check, LoaderCircle, X } from 'lucide-react';
import { cn } from '@/lib/cn';
import { dropTargetId, type DropTarget } from '../model/grid';
import { useDragState, type DragStore, type TargetCheck } from './dragStore';

interface DropZoneProps {
  target: DropTarget;
  /** Поточне місце пари, що її несуть: туди кинути можна (нічого не зміниться). */
  isOrigin: boolean;
  /** У клітинці вже є пари: штрихування не кладемо, щоб їх було видно. */
  occupied: boolean;
  store: DragStore;
}

/** Стан цілі: попередня оцінка → зелена чи червона пунктирна рамка; відповідь сервера → суцільна. */
function tone(check: TargetCheck | undefined, over: boolean): 'idle' | 'free' | 'busy' | 'loading' | 'ok' | 'warn' | 'blocked' | 'error' {
  if (!check) return over ? 'loading' : 'idle';
  if (check.source === 'estimate') {
    if (over) return 'loading';
    return check.status === 'free' ? 'free' : 'busy';
  }
  if (check.status === 'loading') return 'loading';
  if (check.status === 'error') return 'error';
  if (check.status === 'blocked') return 'blocked';
  return check.conflicts.length > 0 ? 'warn' : 'ok';
}

const ZONE_CLASS = {
  idle: 'border border-dashed border-default',
  free: 'border border-dashed border-add bg-add-subtle/45',
  // зайнято — крапкова рамка, вільно — пунктир: різниця не лише в кольорі
  busy: 'border-2 border-dotted border-danger bg-danger-subtle/25',
  loading: 'border-2 border-[var(--border-color-accent)] bg-selected/70',
  ok: 'border-2 border-add bg-add-subtle',
  warn: 'border-2 border-change bg-change-subtle',
  blocked: 'border-2 border-danger bg-danger-subtle',
  error: 'border-2 border-strong bg-sunken',
} as const;

/**
 * Ціль перетягування в клітинці: уся клітинка для цілої пари, верхня чи нижня половина — для половинок.
 * Показується лише під час перетягування; колір — попередня оцінка, після наведення — відповідь сервера.
 */
export function DropZone({ target, isOrigin, occupied, store }: DropZoneProps) {
  const id = dropTargetId(target);
  const { setNodeRef } = useDroppable({ id, data: { target } });
  const over = useDragState(store, (state) => state.overId === id);
  const check = useDragState(store, (state) => state.checks.get(id));
  const state = isOrigin ? 'idle' : tone(check, over);

  return (
    <div
      ref={setNodeRef}
      aria-hidden="true"
      className={cn(
        'pointer-events-none absolute inset-x-0.5 z-20 flex items-center justify-center rounded-plate transition-[background-color,border-color] duration-100',
        target.parity === 'BOTH' && 'inset-y-0.5',
        target.parity === 'NUMERATOR' && 'top-0.5 h-[calc(50%-0.1875rem)]',
        target.parity === 'DENOMINATOR' && 'bottom-0.5 h-[calc(50%-0.1875rem)]',
        isOrigin ? 'border border-dashed border-strong' : ZONE_CLASS[state],
        // порожню зайняту клітинку штрихуємо (як «блок»), а заняття під рамкою лишаємо читабельними
        state === 'busy' && !occupied && 'hatch [--hatch-color:var(--border-color-cancel)]',
        over && 'z-30',
      )}
    >
      {over && !isOrigin ? (
        <span
          className={cn(
            'inline-flex size-5 items-center justify-center rounded-full bg-raised shadow-popover',
            state === 'ok' && 'text-add',
            state === 'warn' && 'text-change',
            (state === 'blocked' || state === 'error') && 'text-danger',
            state === 'loading' && 'text-accent',
          )}
        >
          {state === 'loading' ? <LoaderCircle className="size-3.5 motion-safe:animate-spin" /> : null}
          {state === 'ok' || state === 'warn' ? <Check className="size-3.5" /> : null}
          {state === 'blocked' || state === 'error' ? <X className="size-3.5" /> : null}
        </span>
      ) : null}
    </div>
  );
}
