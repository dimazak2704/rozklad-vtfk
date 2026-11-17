import { useRef } from 'react';
import { Link } from 'react-router';
import type { ChangeView, IsoDate, OrderRowType } from '@/api/types';
import { cn } from '@/lib/cn';
import { roomLabel } from '@/lib/schedule';
import { useEntrance } from '../entrance';
import { GroupHint } from '../GroupHint';
import { reasonHint } from '../schedule/schedule-utils';
import { changeRowText, ROOM_CHANGE_TITLE, sortByPair } from './change-rows';
import { changeGroupKey, isMyGroup, type ChangeGroup } from './changes-filter';

/** Слово стану над рядком — у кольорі стану, без «таблички». Звичайні рядки розпорядження — тихо. */
const STATE: Record<OrderRowType, { word: string; tone: string }> = {
  REPLACEMENT: { word: 'Заміна', tone: 'text-change' },
  CANCELLATION: { word: 'Пари не буде', tone: 'text-cancel' },
  ADDITIONAL: { word: 'Додаткова пара', tone: 'text-add' },
  BLOCK: { word: 'Блок пар', tone: 'text-secondary' },
  FINISHED: { word: 'Вичитано', tone: 'text-secondary' },
  AS_SCHEDULED: { word: 'За розкладом', tone: 'text-secondary' },
  ROOM_CHANGE: { word: 'Інша аудиторія', tone: 'text-room' },
  MERGE_SUBGROUPS: { word: 'Обʼєднання підгруп', tone: 'text-change' },
};

const STRUCK = 'line-through decoration-[var(--text-decoration-color-change)]';

/** Один рядок розпорядження реченням: що тепер (головне), що було (закреслено), де. */
function ChangeRow({
  item,
  mainOrderNumber,
  commonReason,
}: {
  item: ChangeView;
  mainOrderNumber: number | null | undefined;
  commonReason: string | null;
}) {
  const state = STATE[item.rowType];
  const cancelled = item.rowType === 'CANCELLATION';
  const roomChange = item.rowType === 'ROOM_CHANGE';
  const { title, old } = changeRowText(item);
  const room = cancelled ? null : roomLabel(item.room);
  const reason = reasonHint(item.reasonText);
  const hint = reason && reason !== commonReason ? reason : null;
  const fromAmendment = item.orderNumber != null && item.orderNumber !== mainOrderNumber;
  const note = [hint, fromAmendment ? `з доповнення №${item.orderNumber}` : null].filter(Boolean).join(' · ');

  return (
    <li className="grid grid-cols-[minmax(0,1fr)] gap-x-4 py-3 first:pt-0 last:pb-0 sm:grid-cols-[5rem_minmax(0,1fr)_auto]">
      <span className="hidden flex-col pt-[1.125rem] sm:flex">
        <span className="text-ui font-semibold text-primary figures">{item.pairLabel} пара</span>
        {item.subgroupNote ? <span className="text-caption text-muted">{item.subgroupNote}</span> : null}
      </span>
      <span className="flex min-w-0 flex-col gap-0.5">
        <span className="text-meta font-semibold">
          {/* на телефоні пара — у рядку стану, щоб не забирати колонку */}
          <span className="text-primary sm:hidden">
            {item.pairLabel} пара{item.subgroupNote ? `, ${item.subgroupNote}` : ''} ·{' '}
          </span>
          <span className={state.tone}>{state.word}</span>
        </span>
        <span className={cn('text-cell font-[650] text-pretty', cancelled ? cn('text-secondary', 'line-through decoration-[var(--text-decoration-color-cancel)] decoration-2') : title === ROOM_CHANGE_TITLE ? 'font-[500] text-secondary' : 'text-primary')}>
          {cancelled ? <span className="sr-only">Було: </span> : null}
          {title}
          {item.rowType === 'MERGE_SUBGROUPS' ? <span className="font-normal text-secondary"> — обидві підгрупи</span> : null}
        </span>
        {old ? (
          <span className="text-caption text-muted">
            <span className="sr-only">Замість: </span>
            <s className={STRUCK}>{old}</s>
          </span>
        ) : null}
        {note ? <span className="text-caption text-muted">{note}</span> : null}
        {room ? <span className={cn('text-caption sm:hidden figures', roomChange ? 'font-[650] text-room' : 'text-secondary')}>{room}</span> : null}
      </span>
      {room ? (
        <span className={cn('hidden pt-[1.125rem] text-ui whitespace-nowrap sm:block figures', roomChange ? 'font-[650] text-room' : 'text-secondary')}>
          {room}
        </span>
      ) : (
        <span className="hidden sm:block" />
      )}
    </li>
  );
}

interface ChangesListProps {
  groups: readonly ChangeGroup[];
  date: IsoDate;
  myGroupIds: ReadonlySet<string>;
  /** Номер основного розпорядження: рядки з іншим номером надруковані в доповненні. */
  mainOrderNumber?: number | null;
  /** Причина, спільна для більшості рядків (див. `dominantReason`): у рядках її не повторюємо. */
  commonReason?: string | null;
}

/**
 * Заміни дня за групами (DESIGN §8.3): код групи — посилання на її день, під ним кожна зміна одним
 * рядком «що тепер / що було / де». Без табличок-бейджів: стан — словом у своєму кольорі. Свої групи —
 * першими й на тонованому тлі.
 */
export function ChangesList({ groups, date, myGroupIds, mainOrderNumber, commonReason = null }: ChangesListProps) {
  const sorted = [...groups].sort((a, b) => Number(isMyGroup(myGroupIds, b.group.id)) - Number(isMyGroup(myGroupIds, a.group.id)));
  const ref = useRef<HTMLUListElement>(null);
  // перша поява: групи проявляються згори вниз (DESIGN §6)
  useEntrance(ref, sorted.length > 0);
  return (
    <ul ref={ref} className="flex flex-col divide-y divide-[var(--border-color-separator)] rounded-panel border border-default bg-surface">
      {sorted.map(({ group, items }) => {
        const own = isMyGroup(myGroupIds, group.id);
        return (
          <li
            key={changeGroupKey(group)}
            className={cn('grid gap-x-6 gap-y-1 px-4 py-4 first:rounded-t-panel last:rounded-b-panel md:grid-cols-[7rem_minmax(0,1fr)] md:px-5', own && 'bg-selected')}
          >
            <GroupHint code={group.code} when="на цей день">
              <Link
                to={`/g/${encodeURIComponent(group.code)}?date=${date}&view=day`}
                aria-label={`${group.code}${own ? ', ваша група' : ''}: розклад на цей день`}
                className="-ms-1 inline-flex h-11 w-fit items-center self-start rounded-control px-1 font-display text-title font-semibold text-primary figures transition-colors duration-150 hover-fine:text-accent focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus md:mt-1"
              >
                <span className="u-line">{group.code}</span>
              </Link>
            </GroupHint>
            <ol className="flex flex-col divide-y divide-[var(--border-color-separator)]">
              {sortByPair(items).map((item, index) => (
                <ChangeRow key={`${item.pairLabel}-${index}`} item={item} mainOrderNumber={mainOrderNumber} commonReason={commonReason} />
              ))}
            </ol>
          </li>
        );
      })}
    </ul>
  );
}
