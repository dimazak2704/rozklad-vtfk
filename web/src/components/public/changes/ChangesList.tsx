import { ArrowRight, Star } from 'lucide-react';
import { Link } from 'react-router';
import type { ChangeView, IsoDate, OrderRowType } from '@/api/types';
import { Badge, Plate, Tooltip, type BadgeTone } from '@/components/ui';
import { cn } from '@/lib/cn';
import { countLabel } from '@/lib/labels';
import { roomLabel } from '@/lib/schedule';
import { reasonHint } from '../schedule/schedule-utils';
import { changeGroupKey, isMyGroup, type ChangeGroup } from './changes-filter';

const ROW_BADGE: Record<OrderRowType, { label: string; tone: BadgeTone }> = {
  REPLACEMENT: { label: 'Заміна', tone: 'change' },
  CANCELLATION: { label: 'Пари не буде', tone: 'cancel' },
  ADDITIONAL: { label: 'Додатково', tone: 'add' },
  BLOCK: { label: 'Блок пар', tone: 'neutral' },
  FINISHED: { label: 'Вичитано', tone: 'neutral' },
  AS_SCHEDULED: { label: 'За розкладом', tone: 'neutral' },
  ROOM_CHANGE: { label: 'Інша аудиторія', tone: 'room' },
};

const CHANGE_FORMS = ['зміна', 'зміни', 'змін'] as const;

function joined(discipline?: string | null, teacher?: string | null): string {
  return [discipline, teacher].filter(Boolean).join(', ');
}

function Reason({ text }: { text: string | null | undefined }) {
  if (!text) return <span className="text-muted">—</span>;
  const hint = reasonHint(text);
  const chip = (
    <span
      tabIndex={hint ? 0 : undefined}
      className={cn(
        'inline-flex h-6 items-center rounded-plate border border-default bg-sunken px-1.5 text-caption font-semibold text-secondary',
        hint && 'cursor-help focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus',
      )}
    >
      {text}
      {hint ? <span className="sr-only"> — {hint}</span> : null}
    </span>
  );
  return hint ? <Tooltip content={hint}>{chip}</Tooltip> : chip;
}

/** «За розкладом»: те, що було, — закреслене (заміна, скасування) або звичайне (зміна аудиторії, блок). */
function Before({ item }: { item: ChangeView }) {
  if (item.rowType === 'ADDITIONAL') return <span className="font-semibold text-add">Додатково</span>;
  const text = joined(item.from.discipline, item.from.teacher);
  if (!text) return <span className="text-muted">—</span>;
  const struck = item.rowType === 'REPLACEMENT' || item.rowType === 'CANCELLATION';
  return (
    <span className={cn(struck ? 'text-secondary line-through decoration-[var(--text-decoration-color-muted)]' : 'text-primary')}>
      {struck ? <span className="sr-only">Було: </span> : null}
      {text}
    </span>
  );
}

/** «По заміні»: нове заняття, «Пари не буде» або позначка зміни аудиторії. */
function After({ item }: { item: ChangeView }) {
  if (item.rowType === 'CANCELLATION') return <span className="font-semibold text-cancel">Пари не буде</span>;
  if (item.rowType === 'ROOM_CHANGE') return <span className="text-room">Та сама пара в іншій аудиторії</span>;
  const text = joined(item.to.discipline, item.to.teacher);
  if (!text) return <span className="text-muted">—</span>;
  return (
    <span className="font-semibold text-primary">
      <span className="sr-only">Стало: </span>
      {text}
    </span>
  );
}

function Room({ item }: { item: ChangeView }) {
  const room = roomLabel(item.room);
  if (!room) return <span className="text-muted">—</span>;
  return <span className={cn('whitespace-nowrap figures', item.rowType === 'ROOM_CHANGE' ? 'font-semibold text-room' : 'text-primary')}>{room}</span>;
}

function GroupPlate({ code, date, own }: { code: string; date: IsoDate; own: boolean }) {
  return (
    <Link
      to={`/g/${encodeURIComponent(code)}?date=${date}&view=day`}
      aria-label={`${code}${own ? ' (в обраному)' : ''}: розклад на цей день`}
      className="inline-flex items-center gap-1 rounded-plate focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus"
    >
      <Plate size="lg" tone={own ? 'accent' : 'default'} className="hover:border-accent hover:text-accent">
        {own ? <Star aria-hidden="true" className="me-1 size-3.5 fill-current" /> : null}
        {code}
      </Plate>
    </Link>
  );
}

interface ChangesListProps {
  groups: readonly ChangeGroup[];
  date: IsoDate;
  myGroupIds: ReadonlySet<string>;
  /** Номер основного розпорядження: рядки з іншим номером надруковані в доповненні. */
  mainOrderNumber?: number | null;
}

/** «Доповнення №45», якщо рядок надруковано не в основному розпорядженні дати. */
function AmendmentBadge({ item, mainOrderNumber }: { item: ChangeView; mainOrderNumber: number | null | undefined }) {
  if (item.orderNumber == null || item.orderNumber === mainOrderNumber) return null;
  return <Badge size="sm">Доповнення №{item.orderNumber}</Badge>;
}

/**
 * Заміни дня у форматі розпорядження коледжу (DOMAIN §8): Група | Пара | За розкладом | Причина | По заміні | Ауд.
 * На телефоні — картки груп з тими самими рядками.
 */
export function ChangesList({ groups, date, myGroupIds, mainOrderNumber }: ChangesListProps) {
  return (
    <>
      <div className="hidden overflow-hidden rounded-panel border border-default bg-surface md:block">
        <table className="w-full border-collapse text-start text-ui">
          <caption className="sr-only">Заміни за групами</caption>
          <thead className="bg-sunken text-caption font-semibold text-secondary">
            <tr>
              <th scope="col" className="w-28 border-b border-default px-3 py-2.5 text-start">
                Група
              </th>
              <th scope="col" className="w-16 border-b border-default px-3 py-2.5 text-start">
                Пара
              </th>
              <th scope="col" className="border-b border-default px-3 py-2.5 text-start">
                За розкладом
              </th>
              <th scope="col" className="w-24 border-b border-default px-3 py-2.5 text-start">
                Причина
              </th>
              <th scope="col" className="border-b border-default px-3 py-2.5 text-start">
                По заміні
              </th>
              <th scope="col" className="w-24 border-b border-default px-3 py-2.5 text-start">
                Ауд.
              </th>
            </tr>
          </thead>
          {groups.map(({ group, items }) => {
            const own = isMyGroup(myGroupIds, group.id);
            return (
              <tbody key={changeGroupKey(group)} className={cn('border-b border-default last:border-b-0', own && 'bg-selected')}>
                {items.map((item, index) => (
                  <tr key={`${item.pairLabel}-${index}`} className="align-top">
                    {index === 0 ? (
                      <th scope="rowgroup" rowSpan={items.length} className="border-e border-separator px-3 py-2.5 text-start">
                        <GroupPlate code={group.code} date={date} own={own} />
                      </th>
                    ) : null}
                    <td className="px-3 py-2.5">
                      <span className="font-condensed text-heading leading-none font-bold text-primary figures">{item.pairLabel}</span>
                    </td>
                    <td className="px-3 py-2.5">
                      <span className="flex flex-col gap-1">
                        <Before item={item} />
                        <span className="flex flex-wrap gap-1">
                          <Badge tone={ROW_BADGE[item.rowType].tone} size="sm">
                            {ROW_BADGE[item.rowType].label}
                          </Badge>
                          {item.subgroupNote ? <Badge size="sm">{item.subgroupNote}</Badge> : null}
                          <AmendmentBadge item={item} mainOrderNumber={mainOrderNumber} />
                        </span>
                      </span>
                    </td>
                    <td className="px-3 py-2.5">
                      <Reason text={item.reasonText} />
                    </td>
                    <td className="px-3 py-2.5">
                      <After item={item} />
                    </td>
                    <td className="px-3 py-2.5">
                      <Room item={item} />
                    </td>
                  </tr>
                ))}
              </tbody>
            );
          })}
        </table>
      </div>

      <ul className="flex flex-col gap-3 md:hidden">
        {groups.map(({ group, items }) => {
          const own = isMyGroup(myGroupIds, group.id);
          return (
            <li key={changeGroupKey(group)} className={cn('rounded-panel border bg-surface', own ? 'border-accent' : 'border-default')}>
              <div className="flex items-center justify-between gap-3 border-b border-separator px-3.5 py-2.5">
                <GroupPlate code={group.code} date={date} own={own} />
                <Link
                  to={`/g/${encodeURIComponent(group.code)}?date=${date}&view=day`}
                  className="inline-flex items-center gap-1 text-caption font-semibold text-accent"
                  tabIndex={-1}
                  aria-hidden="true"
                >
                  {countLabel(items.length, CHANGE_FORMS)}
                  <ArrowRight className="size-3.5 rtl:rotate-180" />
                </Link>
              </div>
              <ol className="flex flex-col divide-y divide-[var(--border-color-separator)]">
                {items.map((item, index) => (
                  <li key={`${item.pairLabel}-${index}`} className="grid grid-cols-[2.5rem_minmax(0,1fr)] gap-x-3 px-3.5 py-3">
                    <span className="font-condensed text-headline leading-none font-bold text-primary figures">{item.pairLabel}</span>
                    <div className="flex min-w-0 flex-col gap-1.5 text-ui">
                      <span className="flex flex-wrap items-center gap-1.5">
                        <Badge tone={ROW_BADGE[item.rowType].tone} size="sm">
                          {ROW_BADGE[item.rowType].label}
                        </Badge>
                        {item.subgroupNote ? <Badge size="sm">{item.subgroupNote}</Badge> : null}
                        <AmendmentBadge item={item} mainOrderNumber={mainOrderNumber} />
                        {item.reasonText ? <Reason text={item.reasonText} /> : null}
                      </span>
                      <Before item={item} />
                      {item.rowType !== 'ROOM_CHANGE' ? (
                        <span className="flex flex-wrap items-baseline gap-x-2">
                          {item.rowType !== 'ADDITIONAL' ? <ArrowRight aria-hidden="true" className="size-3.5 shrink-0 self-center text-change rtl:rotate-180" /> : null}
                          <After item={item} />
                          {item.room && item.rowType !== 'CANCELLATION' ? (
                            <span className="text-secondary">
                              · <Room item={item} />
                            </span>
                          ) : null}
                        </span>
                      ) : (
                        <span className="flex items-center gap-1.5 text-room">
                          <ArrowRight aria-hidden="true" className="size-3.5 rtl:rotate-180" />
                          <Room item={item} />
                        </span>
                      )}
                    </div>
                  </li>
                ))}
              </ol>
            </li>
          );
        })}
      </ul>
    </>
  );
}
