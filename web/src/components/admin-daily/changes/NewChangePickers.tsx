import { TriangleExclamationPoint } from 'lucide-react';
import type { BellSlotDto, LessonView, Uuid } from '@/api/types';
import { Tooltip } from '@/components/ui';
import { cn } from '@/lib/cn';
import { roomLabel } from '@/lib/schedule';
import { lessonSelectable, type NewChangeMode } from './new-change';

export function FieldError({ text }: { text: string }) {
  return (
    <p className="flex items-center gap-1.5 text-caption font-medium text-danger">
      <TriangleExclamationPoint aria-hidden="true" className="size-3.5" />
      {text}
    </p>
  );
}

interface PairPickerProps {
  /** Номери пар, що є цього дня за дзвінками. */
  pairs: readonly number[];
  slots: readonly BellSlotDto[] | undefined;
  selected: readonly number[];
  /** Пари, які в групи цього дня вже є: номер → дисципліни. */
  busy: ReadonlyMap<number, string>;
  onToggle: (pair: number) => void;
}

/** Номери пар для додаткової пари: одна чи кілька підряд; зайняті в групи — пунктиром і з підказкою. */
export function PairPicker({ pairs, slots, selected, busy: busyPairs, onToggle }: PairPickerProps) {
  return (
    <div role="group" aria-label="Номери пар" className="flex flex-wrap gap-1.5">
      {pairs.map((pair) => {
        const on = selected.includes(pair);
        const slot = slots?.find((s) => s.pair === pair);
        // у групи вже є пара — не забороняємо (буває для підгрупи), але видно до збереження
        const busy = busyPairs.get(pair);
        return (
          <Tooltip key={pair} content={busy ? `У групи вже ${busy}` : null}>
            <button
              type="button"
              aria-pressed={on}
              aria-label={[`${pair} пара`, slot?.startsAt, busy ? `у групи вже ${busy}` : null].filter(Boolean).join(', ')}
              onClick={() => onToggle(pair)}
              className={cn(
                'flex h-11 min-w-14 flex-col items-center justify-center rounded-control border px-2 leading-tight',
                'transition-[background-color,border-color,color] duration-100 ease-out',
                'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus',
                on
                  ? 'border-accent bg-selected text-accent'
                  : busy
                    ? 'border-dashed border-control bg-sunken text-secondary hover-fine:border-strong'
                    : 'border-control bg-surface text-primary hover-fine:border-strong',
              )}
            >
              <span className="font-condensed text-heading font-bold figures">{pair}</span>
              {busy ? (
                <span aria-hidden="true" className="max-w-16 truncate text-meta font-normal text-muted">
                  {busy}
                </span>
              ) : slot ? (
                <span className="text-meta font-normal text-muted figures">{slot.startsAt}</span>
              ) : null}
            </button>
          </Tooltip>
        );
      })}
    </div>
  );
}

interface LessonPickerProps {
  lessons: readonly LessonView[];
  mode: NewChangeMode;
  selectedId: Uuid | null;
  groupChosen: boolean;
  loading: boolean;
  failed: boolean;
  onSelect: (id: Uuid) => void;
  teacherName: (teacher: NonNullable<LessonView['teacher']>) => string;
}

/** Пари групи цього дня: обрати можна ті, що йдуть за розкладом; змінені — з поясненням, чому ні. */
export function LessonPicker({ lessons, mode, selectedId, groupChosen, loading, failed, onSelect, teacherName }: LessonPickerProps) {
  if (!groupChosen) return <p className="text-caption text-muted">Оберіть групу й дату — тут зʼявляться її пари.</p>;
  if (loading) return <p className="text-caption text-muted">Дивимося розклад групи…</p>;
  if (failed) return <p className="text-caption text-danger">Розклад групи не завантажився. Спробуйте іншу дату чи оновіть сторінку.</p>;
  if (lessons.length === 0) return <p className="text-caption text-secondary">Цього дня в групи пар немає.</p>;
  return (
    <div role="radiogroup" aria-label="Пари групи" className="flex flex-col gap-1">
      {lessons.map((lesson) => {
        const selectable = lessonSelectable(lesson, mode);
        const selected = lesson.id === selectedId;
        return (
          <button
            key={lesson.id}
            type="button"
            role="radio"
            aria-checked={selected}
            disabled={!selectable}
            onClick={() => onSelect(lesson.id)}
            className={cn(
              'grid grid-cols-[2.5rem_minmax(0,1fr)] items-baseline gap-x-2 rounded-control border px-3 py-2 text-start',
              'transition-[background-color,border-color] duration-100 ease-out',
              'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus',
              selected ? 'border-accent bg-selected' : 'border-default bg-surface hover-fine:enabled:bg-hover',
              !selectable && 'cursor-not-allowed opacity-60',
            )}
          >
            <span className="font-condensed text-heading font-bold text-primary figures">{lesson.pair}</span>
            <span className="flex min-w-0 flex-col">
              <span className="truncate text-ui font-[650] text-primary">
                {lesson.discipline.shortName}
                {lesson.subgroup > 0 ? <span className="font-normal text-secondary"> · {lesson.subgroup} п/г</span> : null}
              </span>
              <span className="truncate text-caption text-secondary">
                {[lesson.teacher ? teacherName(lesson.teacher) : null, roomLabel(lesson.room?.name), lesson.startsAt].filter(Boolean).join(' · ')}
                {selectable ? null : ' · уже змінено'}
              </span>
            </span>
          </button>
        );
      })}
    </div>
  );
}
