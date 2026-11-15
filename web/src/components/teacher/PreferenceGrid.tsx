import { Ban, ThumbsDown } from 'lucide-react';
import { useRef, useState, type KeyboardEvent } from 'react';
import type { BellSlotDto, PreferenceKind, Weekday } from '@/api/types';
import { cn } from '@/lib/cn';
import { capitalize, weekdayName, weekdayShort } from '@/lib/labels';
import { cellKey, KIND_CELL, KIND_TEXT, nextKind, shorterDaysNote, WEEKDAYS, type CellKey, type PreferenceMap } from './preferences';

/** Значок стану: на телефоні замість слова, ширше — поруч зі словом. */
function KindIcon({ kind, className }: { kind: PreferenceKind; className?: string }) {
  const Icon = kind === 'UNWANTED' ? ThumbsDown : Ban;
  return <Icon aria-hidden="true" className={cn('size-4 shrink-0', className)} />;
}

/**
 * Ключ до станів одним рядком: значок і слово. На телефоні в клітинці лише значок, тож без ключа
 * «небажано» від «неможливо» не відрізнити.
 */
export function PreferenceLegend({ className }: { className?: string }) {
  return (
    <p className={cn('flex flex-wrap items-center gap-x-4 gap-y-1 text-caption font-[600]', className)}>
      {(['UNWANTED', 'UNAVAILABLE'] as const).map((kind) => (
        <span key={kind} className={cn('inline-flex items-center gap-1.5', kind === 'UNWANTED' ? 'text-change' : 'text-cancel')}>
          <KindIcon kind={kind} />
          {KIND_TEXT[kind]}
        </span>
      ))}
    </p>
  );
}

interface PreferenceGridProps {
  value: PreferenceMap;
  onCellChange: (key: CellKey, kind: PreferenceKind | null) => void;
  /** Дзвінки профілю, що діє, по днях: скільки пар і о котрій. */
  slotsByWeekday: ReadonlyMap<Weekday, readonly BellSlotDto[]>;
  /** Мої пари в клітинці за базовим розкладом семестру: «4КІ1 · СТП (чисельник)». */
  lessons?: ReadonlyMap<CellKey, readonly string[]>;
  describedBy?: string;
}

/**
 * Сітка «день × пара». Клік або Enter/пробіл — наступний стан (порожньо → небажано → неможливо);
 * стрілки — переміщення, «1» — небажано, «2» — неможливо, «0» чи Delete — очистити.
 */
export function PreferenceGrid({ value, onCellChange, slotsByWeekday, lessons, describedBy }: PreferenceGridProps) {
  const maxPairs = Math.max(0, ...[...slotsByWeekday.values()].map((slots) => slots.length));
  const pairs = Array.from({ length: maxPairs }, (_, index) => index + 1);
  const exists = (weekday: number, pair: number) => (slotsByWeekday.get(weekday as Weekday)?.length ?? 0) >= pair;
  const [active, setActive] = useState<CellKey>(cellKey(1, 1));
  const buttons = useRef(new Map<CellKey, HTMLButtonElement>());

  const move = (weekday: number, pair: number) => {
    if (weekday < 1 || weekday > 5 || pair < 1 || pair > maxPairs || !exists(weekday, pair)) return;
    const key = cellKey(weekday, pair);
    setActive(key);
    buttons.current.get(key)?.focus();
  };

  const onKeyDown = (event: KeyboardEvent<HTMLButtonElement>, weekday: number, pair: number) => {
    const key = cellKey(weekday, pair);
    switch (event.key) {
      case 'ArrowRight':
        move(weekday + 1, pair);
        break;
      case 'ArrowLeft':
        move(weekday - 1, pair);
        break;
      case 'ArrowDown':
        move(weekday, pair + 1);
        break;
      case 'ArrowUp':
        move(weekday, pair - 1);
        break;
      case 'Home':
        move(1, pair);
        break;
      case 'End':
        move(exists(5, pair) ? 5 : 4, pair);
        break;
      case '1':
        onCellChange(key, 'UNWANTED');
        break;
      case '2':
        onCellChange(key, 'UNAVAILABLE');
        break;
      case '0':
      case 'Delete':
      case 'Backspace':
        onCellChange(key, null);
        break;
      default:
        return;
    }
    event.preventDefault();
  };

  const note = shorterDaysNote(slotsByWeekday, maxPairs);

  return (
    <div className="flex flex-col gap-2">
      <div className="overflow-x-auto rounded-panel border border-default bg-surface">
        <table className="w-full min-w-[18rem] table-fixed border-separate border-spacing-0 sm:min-w-[34rem]">
          <caption className="sr-only">Побажання: дні тижня й пари</caption>
          <colgroup>
            <col className="w-11 sm:w-[5.5rem]" />
            {WEEKDAYS.map((weekday) => (
              <col key={weekday} />
            ))}
          </colgroup>
          <thead>
            <tr>
              <th scope="col" className="h-12 border-b border-separator px-2 sm:px-4">
                <span className="sr-only">Пара</span>
              </th>
              {WEEKDAYS.map((weekday) => (
                <th key={weekday} scope="col" className="h-12 border-b border-separator px-2 text-start text-ui font-[600] text-primary sm:px-3">
                  <span className="lg:hidden">{weekdayShort(weekday)}</span>
                  <span className="hidden lg:inline">{capitalize(weekdayName(weekday))}</span>
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {pairs.map((pair, index) => {
              const rowTime = slotsByWeekday.get(1)?.find((s) => s.pair === pair);
              // як у сітці тижня: лише лінії між рядками, без рамки довкола кожної клітинки
              const line = index < pairs.length - 1 ? 'border-b border-separator' : '';
              return (
                <tr key={pair}>
                  <th scope="row" className={cn('px-2 py-2 text-start align-top sm:px-4', line)}>
                    <span className="block text-title leading-7 font-semibold text-primary figures">{pair}</span>
                    {rowTime ? <span className="text-caption font-normal text-secondary figures">{rowTime.startsAt}</span> : null}
                  </th>
                  {WEEKDAYS.map((weekday) => {
                    if (!exists(weekday, pair)) {
                      return (
                        <td key={weekday} className={cn('p-1', line)}>
                          {/* у цей день стільки пар за дзвінками немає: заштриховане місце, а пояснення — один раз під сіткою */}
                          <span aria-hidden="true" className="block h-14 rounded-cell hatch" />
                          <span className="sr-only">пари немає</span>
                        </td>
                      );
                    }
                    const key = cellKey(weekday, pair);
                    const kind = value.get(key) ?? null;
                    const slot = slotsByWeekday.get(weekday)?.find((s) => s.pair === pair);
                    const mine = lessons?.get(key) ?? [];
                    const state = kind ? KIND_TEXT[kind] : 'без побажань';
                    return (
                      <td key={weekday} className={cn('p-1', line)}>
                        <button
                          ref={(element) => {
                            if (element) buttons.current.set(key, element);
                            else buttons.current.delete(key);
                          }}
                          type="button"
                          tabIndex={key === active ? 0 : -1}
                          aria-label={`${capitalize(weekdayName(weekday))}, ${pair} пара${slot ? ` (${slot.startsAt}–${slot.endsAt})` : ''}: ${state}${mine.length > 0 ? `. Ваші пари тут: ${mine.join('; ')}` : ''}`}
                          aria-describedby={describedBy}
                          // код групи в клітинці пояснює підказка, а не окрема примітка під сіткою
                          title={mine.length > 0 ? `Ваша пара тут за розкладом семестру: ${mine.join('; ')}` : undefined}
                          onFocus={() => setActive(key)}
                          onClick={() => onCellChange(key, nextKind(kind))}
                          onKeyDown={(event) => onKeyDown(event, weekday, pair)}
                          className={cn(
                            'flex h-14 w-full flex-col items-start justify-start gap-0.5 rounded-cell px-1.5 py-1.5 text-start sm:px-2',
                            'transition-[background-color] duration-100 ease-out',
                            'focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-focus',
                            kind ? KIND_CELL[kind] : 'hover-fine:bg-hover active:bg-active',
                          )}
                        >
                          {kind ? (
                            <span className="inline-flex max-w-full items-center gap-1 text-caption font-[650]">
                              <KindIcon kind={kind} />
                              <span className="hidden truncate sm:inline">{KIND_TEXT[kind]}</span>
                            </span>
                          ) : null}
                          {mine.length > 0 ? (
                            <span className="max-w-full truncate text-caption font-[500] text-secondary figures">
                              {mine.map((label) => label.split(' · ')[0]).join(', ')}
                            </span>
                          ) : null}
                        </button>
                      </td>
                    );
                  })}
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
      {note ? <p className="text-caption text-secondary">{note}</p> : null}
    </div>
  );
}
