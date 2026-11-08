import { Check, LoaderCircle, TriangleExclamationPoint, X } from 'lucide-react';
import { createPortal } from 'react-dom';
import { cn } from '@/lib/cn';
import { capitalize, weekdayName } from '@/lib/labels';
import type { DropTarget } from '../model/grid';
import { PARITY_SHORT } from '../model/labels';
import type { TargetCheck } from './dragStore';
import { PREVIEW_CARD_WIDTH, type CardPosition } from './position';

const MAX_LINES = 3;

interface DropPreviewCardProps {
  target: DropTarget;
  check: TargetCheck | undefined;
  position: CardPosition;
  /** Назва аудиторії, якщо пара переїде в іншу (подання «Аудиторії»). */
  roomChange: string | null;
}

/** Пояснення поруч із ціллю під курсором: вільно, з попередженнями чи конфлікт — словами сервера. */
export function DropPreviewCard({ target, check, position, roomChange }: DropPreviewCardProps) {
  const slot = `${capitalize(weekdayName(target.weekday))}, ${target.pair} пара · ${PARITY_SHORT[target.parity]}`;
  let tone: 'neutral' | 'ok' | 'warn' | 'blocked' = 'neutral';
  let title = 'Перевіряємо слот…';
  let lines: string[] = [];
  if (check?.source === 'estimate') {
    title = check.status === 'free' ? 'Схоже, вільно — перевіряємо…' : 'Схоже, зайнято — перевіряємо…';
    lines = check.messages;
  } else if (check?.source === 'server' && check.status === 'error') {
    title = 'Не вдалося перевірити слот';
    lines = [`${check.message} Пару можна поставити — сервер перевірить ще раз.`];
  } else if (check?.source === 'server' && check.status === 'blocked') {
    tone = 'blocked';
    title = 'Не можна: зʼявиться конфлікт';
    lines = check.conflicts.map((c) => c.message);
  } else if (check?.source === 'server' && check.status === 'ok') {
    tone = check.conflicts.length > 0 ? 'warn' : 'ok';
    title = check.conflicts.length > 0 ? 'Можна, але будуть попередження' : 'Вільно — можна ставити';
    lines = check.conflicts.map((c) => c.message);
  }
  const loading = !check || check.source === 'estimate' || check.status === 'loading';

  return createPortal(
    <div
      role="status"
      aria-live="polite"
      data-testid="drop-preview"
      data-state={loading ? 'loading' : tone}
      style={{ left: position.left, top: position.top, width: PREVIEW_CARD_WIDTH }}
      className="pointer-events-none fixed z-[60] rounded-panel border border-default bg-raised p-3 text-primary shadow-popover"
    >
      <p className="text-meta font-semibold text-muted">
        {slot}
        {roomChange ? ` · ${roomChange}` : ''}
      </p>
      <p
        className={cn(
          'mt-1 flex items-center gap-1.5 text-ui font-semibold',
          tone === 'ok' && 'text-add',
          tone === 'warn' && 'text-change',
          tone === 'blocked' && 'text-danger',
        )}
      >
        {loading ? <LoaderCircle aria-hidden="true" className="size-4 text-accent motion-safe:animate-spin" /> : null}
        {!loading && tone === 'ok' ? <Check aria-hidden="true" className="size-4" /> : null}
        {!loading && tone === 'warn' ? <TriangleExclamationPoint aria-hidden="true" className="size-4" /> : null}
        {!loading && tone === 'blocked' ? <X aria-hidden="true" className="size-4" /> : null}
        {title}
      </p>
      {lines.length > 0 ? (
        <ul className="mt-1.5 flex flex-col gap-1 text-caption text-secondary">
          {lines.slice(0, MAX_LINES).map((line) => (
            <li key={line}>{line}</li>
          ))}
          {lines.length > MAX_LINES ? <li className="text-muted">і ще {lines.length - MAX_LINES}</li> : null}
        </ul>
      ) : null}
    </div>,
    document.body,
  );
}
