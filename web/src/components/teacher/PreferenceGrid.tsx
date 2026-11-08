import { useRef, useState, type KeyboardEvent } from 'react';
import type { BellSlotDto, PreferenceKind, Weekday } from '@/api/types';
import { cn } from '@/lib/cn';
import { capitalize, weekdayName, weekdayShort } from '@/lib/labels';
import { cellKey, KIND_CELL, KIND_TEXT, nextKind, type CellKey, type PreferenceMap } from './preferences';

const WEEKDAYS: readonly Weekday[] = [1, 2, 3, 4, 5];

interface PreferenceGridProps {
  value: PreferenceMap;
  onCellChange: (key: CellKey, kind: PreferenceKind | null) => void;
  /** Дзвінки профілю, що діє, по днях: скільки пар і о котрій. */
  slotsByWeekday: ReadonlyMap<Weekday, readonly BellSlotDto[]>;
  /** Мої пари в клітинці за поточним розкладом: «4КІ1 · СТП (чисельник)». */
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

  return (
    <div className="overflow-x-auto rounded-panel border border-default bg-surface">
      <table className="w-full min-w-[20rem] table-fixed border-collapse sm:min-w-[34rem]">
        <caption className="sr-only">Побажання: дні тижня й пари</caption>
        <colgroup>
          <col className="w-11 sm:w-[5.5rem]" />
          {WEEKDAYS.map((weekday) => (
            <col key={weekday} />
          ))}
        </colgroup>
        <thead>
          <tr className="bg-sunken">
            <th scope="col" className="border-b border-default px-2 py-2 text-start text-caption font-semibold text-secondary">
              Пара
            </th>
            {WEEKDAYS.map((weekday) => (
              <th key={weekday} scope="col" className="border-b border-default px-2 py-2 text-start text-ui font-semibold text-primary">
                <span className="lg:hidden">{weekdayShort(weekday)}</span>
                <span className="hidden lg:inline">{capitalize(weekdayName(weekday))}</span>
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {pairs.map((pair) => {
            const rowTime = slotsByWeekday.get(1)?.find((s) => s.pair === pair);
            return (
              <tr key={pair} className="border-b border-separator last:border-b-0">
                <th scope="row" className="px-2 py-1.5 text-start align-top">
                  <span className="block font-condensed text-headline leading-none font-bold text-primary figures">{pair}</span>
                  {rowTime ? <span className="text-meta font-normal text-muted figures">{rowTime.startsAt}</span> : null}
                </th>
                {WEEKDAYS.map((weekday) => {
                  if (!exists(weekday, pair)) {
                    return (
                      <td key={weekday} className="p-1">
                        <span className="flex h-14 items-center justify-center rounded-plate border border-dashed border-separator text-caption text-disabled">
                          <span className="hidden sm:inline">пари немає</span>
                        </span>
                      </td>
                    );
                  }
                  const key = cellKey(weekday, pair);
                  const kind = value.get(key) ?? null;
                  const slot = slotsByWeekday.get(weekday)?.find((s) => s.pair === pair);
                  const mine = lessons?.get(key) ?? [];
                  const state = kind ? KIND_TEXT[kind] : 'без побажань';
                  return (
                    <td key={weekday} className="p-1">
                      <button
                        ref={(element) => {
                          if (element) buttons.current.set(key, element);
                          else buttons.current.delete(key);
                        }}
                        type="button"
                        tabIndex={key === active ? 0 : -1}
                        aria-label={`${capitalize(weekdayName(weekday))}, ${pair} пара${slot ? ` (${slot.startsAt}–${slot.endsAt})` : ''}: ${state}${mine.length > 0 ? `. Зараз тут: ${mine.join('; ')}` : ''}`}
                        aria-describedby={describedBy}
                        title={mine.length > 0 ? `Зараз тут: ${mine.join('; ')}` : undefined}
                        onFocus={() => setActive(key)}
                        onClick={() => onCellChange(key, nextKind(kind))}
                        onKeyDown={(event) => onKeyDown(event, weekday, pair)}
                        className={cn(
                          'flex h-14 w-full flex-col items-start justify-between rounded-plate border px-1.5 py-1.5 text-start sm:px-2',
                          'transition-[background-color,border-color] duration-100 ease-out',
                          'focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-focus',
                          kind ? KIND_CELL[kind] : 'border-default bg-surface hover:border-strong hover:bg-hover',
                        )}
                      >
                        <span className={cn('hidden text-caption font-semibold sm:inline', !kind && 'invisible')}>{kind ? KIND_TEXT[kind] : '·'}</span>
                        {mine.length > 0 ? (
                          <span className="max-w-full truncate text-meta font-normal text-muted">
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
  );
}
