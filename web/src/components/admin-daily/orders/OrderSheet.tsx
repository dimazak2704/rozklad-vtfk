import type { ReactNode } from 'react';
import type { ChangeDto, OrderDto, OrderItemDto, Uuid } from '@/api/types';
import { HoverCard, HoverCardContent, HoverCardTrigger } from '@/components/ui';
import { cn } from '@/lib/cn';
import { formatLongDate } from '@/lib/date';
import { CHANGE_KIND_LABEL, CHANGE_STATUS_LABEL } from '@/lib/labels';
import { ChangeTimeline } from '../ChangeTimeline';

/** Ширини колонок як у Word-шаблоні коледжу (сума — 100%). */
const COLUMNS = ['9%', '6%', '14%', '17%', '11%', '14%', '18%', '11%'] as const;
const CELL = 'border border-current px-[0.35em] py-[0.15em] align-top';
const SERIF = '"Times New Roman", "Liberation Serif", Tinos, "Noto Serif", Times, serif';

/** «РОЗПОРЯДЖЕННЯ» розрідженими літерами — як у шапці документа (самі літери, не CSS-капітель). */
function Spaced({ children }: { children: string }) {
  return <span className="tracking-[0.42em]">{children}</span>;
}

function Text({ value }: { value: string | null }) {
  // кілька підгруп сервер передає через перенесення рядка: «Харченко О.\nМихальчук», «304/309»
  return value ? <span className="whitespace-pre-line">{value}</span> : null;
}

function Merged({ children, span }: { children: ReactNode; span: number }) {
  return (
    <td colSpan={span} className={CELL}>
      {children}
    </td>
  );
}

/** Комірки рядка за його типом (DOMAIN §8): обʼєднані «ДОДАТКОВО», «За розкладом», «Пари не буде». */
function RowCells({ item }: { item: OrderItemDto }) {
  const left =
    item.rowType === 'ADDITIONAL' ? (
      <Merged span={2}>ДОДАТКОВО</Merged>
    ) : item.rowType === 'AS_SCHEDULED' ? (
      <Merged span={2}>За розкладом</Merged>
    ) : (
      <>
        <td className={CELL}>
          <Text value={item.fromDiscipline} />
        </td>
        <td className={CELL}>
          <Text value={item.fromTeacher} />
        </td>
      </>
    );
  const right =
    item.rowType === 'CANCELLATION' ? (
      <Merged span={2}>Пари не буде</Merged>
    ) : item.rowType === 'MERGE_SUBGROUPS' ? (
      // як DOCX core: «Обʼєднання підгруп: Ін.мова (Можаровська) — обидві підгрупи»
      <Merged span={2}>
        Обʼєднання підгруп: {item.toDiscipline ?? '—'}
        {item.toTeacher ? ` (${item.toTeacher})` : ''} — обидві підгрупи
      </Merged>
    ) : (
      <>
        <td className={CELL}>
          <Text value={item.toDiscipline} />
        </td>
        <td className={CELL}>
          <Text value={item.toTeacher} />
        </td>
      </>
    );
  return (
    <>
      <td className={CELL}>{item.groupCode}</td>
      <td className={cn(CELL, 'whitespace-nowrap')}>{item.pairLabel}</td>
      {left}
      <td className={CELL}>
        <Text value={item.reasonText} />
      </td>
      {right}
      <td className={CELL}>
        <Text value={item.roomText} />
      </td>
    </>
  );
}

function ChangeHover({ change }: { change: ChangeDto }) {
  return (
    <div className="flex w-[min(20rem,calc(100vw-3rem))] flex-col gap-2.5 font-sans">
      <p className="text-ui font-semibold text-primary">
        {CHANGE_KIND_LABEL[change.kind]} · {change.group.code}, {change.pairFrom} пара
      </p>
      <p className="text-caption text-secondary">Стан: {CHANGE_STATUS_LABEL[change.status]}</p>
      <ChangeTimeline change={change} />
    </div>
  );
}

interface OrderSheetProps {
  order: OrderDto;
  /** Номер основного розпорядження — для шапки доповнення. */
  parentNumber?: number | null;
  /** Зміни дати: наведення на рядок показує, хто й коли її погодив. */
  changes?: ReadonlyMap<Uuid, ChangeDto>;
  /** Рядки, що щойно зʼявилися (живе оновлення). */
  fresh?: ReadonlySet<Uuid>;
  className?: string;
}

/**
 * Аркуш розпорядження у форматі коледжу (DOMAIN §8, фото seed/orders): A4, шрифт із засічками, шапка
 * з обʼєднаними колонками, усі типи рядків, «ЗАМІНА АУДИТОРІЙ», примітки й підпис. Завжди світлий
 * («острівець» data-theme="light"), масштабується за шириною, як сторінка в переглядачі.
 */
export function OrderSheet({ order, parentNumber = null, changes, fresh, className }: OrderSheetProps) {
  const draft = order.status === 'DRAFT';
  const number = order.number !== null ? `№${order.number}` : '№ ____';
  const empty = order.items.length === 0 && order.roomChanges.length === 0;
  const label =
    order.kind === 'AMENDMENT'
      ? `Доповнення ${number} до розпорядження №${parentNumber ?? '—'} на ${formatLongDate(order.date)}`
      : `Розпорядження ${number} на ${formatLongDate(order.date)}`;

  return (
    <div data-theme="light" className={cn('@container mx-auto w-full max-w-[52rem]', className)}>
      <article
        aria-label={draft ? `${label} (чернетка)` : label}
        className="relative min-h-[141.4cqw] overflow-hidden border border-default bg-surface px-[7cqw] pt-[6cqw] pb-[8cqw] text-primary"
        style={{ fontFamily: SERIF, fontSize: 'clamp(8.5px, 1.95cqw, 15.5px)', lineHeight: 1.25 }}
      >
        {draft ? (
          <span
            aria-hidden="true"
            className="pointer-events-none absolute top-[52%] left-1/2 -translate-x-1/2 -rotate-[24deg] text-[7em] font-bold tracking-[0.18em] whitespace-nowrap text-[var(--border-color-separator)] select-none"
          >
            ЧЕРНЕТКА
          </span>
        ) : null}

        <header className="relative mb-[1.2em] text-center font-bold">
          <p className="text-[1.2em]">
            {order.kind === 'AMENDMENT' ? (
              <>
                <Spaced>ДОПОВНЕННЯ</Spaced> {number}
                <span className="block text-[0.85em] font-bold">до розпорядження №{parentNumber ?? '—'}</span>
              </>
            ) : (
              <>
                <Spaced>РОЗПОРЯДЖЕННЯ</Spaced> {number}
              </>
            )}
          </p>
          {/* рядок під заголовком і дату друкуємо так, як їх сформував core */}
          <p>{order.subtitle}</p>
          <p>{order.dateLabel}</p>
          <p className="text-[1.08em]">
            {order.weekdayLabel} - {order.parityLabel}
          </p>
        </header>

        <table className="relative w-full table-fixed border-collapse text-[0.92em]">
          <colgroup>
            {COLUMNS.map((width, index) => (
              <col key={index} style={{ width }} />
            ))}
          </colgroup>
          <thead>
            <tr className="text-center font-normal italic">
              <th scope="col" className={cn(CELL, 'font-normal')}>
                Група
              </th>
              <th scope="col" className={cn(CELL, 'font-normal')}>
                Пара
              </th>
              <th scope="colgroup" colSpan={2} className={cn(CELL, 'font-normal')}>
                За розкладом
              </th>
              <th scope="col" className={cn(CELL, 'font-normal')}>
                Причина заміни
              </th>
              <th scope="colgroup" colSpan={2} className={cn(CELL, 'font-normal')}>
                По заміні
              </th>
              <th scope="col" className={cn(CELL, 'font-normal')}>
                Ауд
              </th>
            </tr>
          </thead>
          <tbody>
            {empty ? (
              <tr>
                <td colSpan={8} className={cn(CELL, 'py-[1em] text-center font-sans text-[0.9em] text-muted')}>
                  Підтверджених змін на цю дату ще немає
                </td>
              </tr>
            ) : null}
            {order.items.map((item) => {
              const change = item.changeId ? changes?.get(item.changeId) : undefined;
              const row = (
                <tr
                  key={item.id}
                  className={cn(
                    change && 'cursor-help transition-[background-color] duration-100 hover:bg-[var(--background-color-selected)]',
                    item.changeId && fresh?.has(item.changeId) && 'motion-safe:animate-[live-flash_2.4s_var(--ease-out)]',
                  )}
                >
                  <RowCells item={item} />
                </tr>
              );
              if (!change) return row;
              return (
                <HoverCard key={item.id} openDelay={350} closeDelay={100}>
                  <HoverCardTrigger asChild>{row}</HoverCardTrigger>
                  <HoverCardContent side="top" align="center">
                    <ChangeHover change={change} />
                  </HoverCardContent>
                </HoverCard>
              );
            })}
            <tr aria-hidden="true">
              <td colSpan={8} className={cn(CELL, 'h-[1.4em]')} />
            </tr>
            <tr>
              <th scope="rowgroup" colSpan={8} className={cn(CELL, 'text-center font-normal')}>
                <span className="tracking-[0.12em]">ЗАМІНА&nbsp;&nbsp;АУДИТОРІЙ</span>
              </th>
            </tr>
            {order.roomChanges.map((item) => (
              <tr key={item.id}>
                <td className={CELL}>{item.groupCode}</td>
                <td className={CELL}>{item.pairLabel}</td>
                <td colSpan={5} className={CELL} />
                <td className={CELL}>
                  <Text value={item.roomText} />
                </td>
              </tr>
            ))}
            {order.roomChanges.length === 0 ? (
              <tr aria-hidden="true">
                <td colSpan={8} className={cn(CELL, 'h-[1.4em]')} />
              </tr>
            ) : null}
          </tbody>
        </table>

        {order.notes.length > 0 ? (
          <div className="relative mt-[1.6em] flex flex-col gap-[0.15em] ps-[3em] font-bold">
            {order.notes.map((note, index) => (
              <p key={`${index}-${note}`}>{note}</p>
            ))}
          </div>
        ) : null}

        <footer className="relative mt-[2.2em] flex items-baseline justify-between gap-[2em] ps-[3em] pe-[2em] font-bold">
          <span>{order.signatoryPosition}</span>
          <span>{order.signatoryName}</span>
        </footer>
      </article>
    </div>
  );
}
