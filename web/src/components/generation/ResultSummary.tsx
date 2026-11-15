import { ArrowDown, ArrowUp, Minus } from 'lucide-react';
import { Fragment } from 'react';
import type { ConstraintAnalysisDto, GenerationWeights } from '@/api/types';
import { cn } from '@/lib/cn';
import { countLabel } from '@/lib/labels';
import { Disclosure } from './Disclosure';
import { GROUP_COLUMNS, splitAnalysis, type ComparisonRow, type GroupComparisonRow } from './model';

const TIMES_FORMS = ['раз', 'рази', 'разів'] as const;
const CONSTRAINT_FORMS = ['обмеження', 'обмеження', 'обмежень'] as const;

/**
 * Різниця «Стало − Було»: менше — краще (зелене), більше — гірше (червоне). Правило, яке задача не враховувала,
 * не «погіршилось» — його різниця нейтральна.
 */
function Delta({ before, after, neutral = false }: { before: number | null; after: number | null; neutral?: boolean }) {
  if (before === null || after === null) return <span className="text-muted">—</span>;
  const delta = after - before;
  return (
    <span
      data-tone={neutral || delta === 0 ? 'neutral' : delta < 0 ? 'better' : 'worse'}
      className={cn(
        'inline-flex items-center justify-end gap-0.5 font-semibold',
        neutral ? 'text-secondary' : [delta < 0 && 'text-add', delta > 0 && 'text-danger'],
        delta === 0 && 'text-muted',
      )}
    >
      {delta < 0 ? <ArrowDown aria-hidden="true" className="size-3.5" /> : null}
      {delta > 0 ? <ArrowUp aria-hidden="true" className="size-3.5" /> : null}
      {delta === 0 ? <Minus aria-hidden="true" className="size-3.5" /> : null}
      <span className="sr-only">{delta < 0 ? 'менше на' : delta > 0 ? 'більше на' : 'без змін'}</span>
      {delta === 0 ? null : Math.abs(delta)}
    </span>
  );
}

/**
 * «Було / Стало» з різницею. Коли «Було» невідоме, таблиця показує лише «Стало» — без вигаданого нуля,
 * з якого все виглядає погіршенням; невідоме «Було» одного рядка — «—» без різниці.
 */
export function MetricsComparison({ rows }: { rows: readonly ComparisonRow[] }) {
  const hasBefore = rows.some((row) => row.before !== null);
  return (
    <table className="w-full border-separate border-spacing-0 text-ui">
      <caption className="pb-2 text-start text-heading font-bold text-primary">{hasBefore ? 'Було і стало' : 'Що вийшло'}</caption>
      <thead>
        <tr className="text-caption text-secondary">
          <th scope="col" className="border-b border-separator py-1.5 pe-3 text-start font-[650]">
            Показник
          </th>
          {hasBefore ? (
            <th scope="col" className="border-b border-separator px-3 py-1.5 text-end font-[650]">
              Було
            </th>
          ) : null}
          <th scope="col" className="border-b border-separator px-3 py-1.5 text-end font-[650]">
            Стало
          </th>
          {hasBefore ? (
            <th scope="col" className="border-b border-separator py-1.5 ps-3 text-end font-[650]">
              Різниця
            </th>
          ) : null}
        </tr>
      </thead>
      <tbody>
        {rows.map((row) => (
          <tr key={row.key}>
            <th scope="row" className={cn('border-b border-separator py-2 pe-3 text-start font-normal', row.ignored ? 'text-secondary' : 'text-primary')}>
              {row.label}
              {row.ignored ? <span className="block text-caption text-muted">не враховувалось</span> : null}
            </th>
            {hasBefore ? <td className="border-b border-separator px-3 py-2 text-end text-secondary figures">{row.before ?? '—'}</td> : null}
            <td className="border-b border-separator px-3 py-2 text-end font-semibold text-primary figures">{row.after}</td>
            {hasBefore ? (
              <td className="border-b border-separator py-2 ps-3 text-end figures">
                <Delta before={row.before} after={row.after} neutral={row.ignored} />
              </td>
            ) : null}
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/**
 * Аналіз обмежень від розвʼязувача (genfix B7): «Що лишилось» — лише порушення в парах, які генератор
 * міг рухати; бонус «половинки в парі» — у «Що вдалося»; порушення в закріплених парах поза вибором —
 * одним приглушеним рядком. Бали оцінки не показуємо — людині вони нічого не кажуть.
 */
export function ConstraintAnalysis({
  items,
  scoped,
  ignored,
}: {
  items: readonly ConstraintAnalysisDto[];
  scoped: boolean;
  /** Правила задачі з «Не враховувати» (вага 0): їх не показуємо як порушення. */
  ignored?: ReadonlySet<keyof GenerationWeights>;
}) {
  const { remaining, achieved, clean, outsideScope, ignored: notCounted } = splitAnalysis(items, ignored);
  return (
    <div className="flex flex-col gap-5">
      <section aria-labelledby="analysis-title" className="flex flex-col gap-2">
        <h3 id="analysis-title" className="text-heading font-bold text-primary">
          Що лишилось
        </h3>
        {remaining.length === 0 ? (
          <p className="text-ui text-secondary">{scoped ? 'У вибраних групах жодне обмеження не порушене.' : 'Жодне обмеження не порушене.'}</p>
        ) : (
          <ul className="flex flex-col divide-y divide-[var(--border-color-separator)] border-y border-separator">
            {remaining.map((item) => (
              <li key={item.key} className="flex items-baseline gap-3 py-2">
                <span
                  aria-hidden="true"
                  className={cn('size-2 shrink-0 translate-y-[-1px] rounded-full', item.hard ? 'bg-[var(--fill-cancel)]' : 'bg-[var(--fill-change)]')}
                />
                <span className="min-w-0 flex-1 text-ui text-primary">
                  {item.constraint}
                  {item.hard ? <span className="text-caption text-cancel"> · жорстке</span> : null}
                </span>
                <span className="text-caption text-secondary figures">{countLabel(item.count, TIMES_FORMS)}</span>
              </li>
            ))}
          </ul>
        )}
        {outsideScope > 0 ? (
          <p className="text-caption text-muted">Ще {outsideScope} — у закріплених парах поза вибором.</p>
        ) : null}
        {notCounted.length > 0 ? (
          <ul aria-label="Не враховувалось" className="flex flex-col gap-1">
            {notCounted.map((item) => (
              <li key={item.key} className="flex items-baseline gap-3 text-caption text-muted">
                <span aria-hidden="true" className="size-2 shrink-0 translate-y-[-1px] rounded-full border-[1.5px] border-[var(--fill-muted)]" />
                <span className="min-w-0 flex-1">
                  {item.constraint} · не враховувалось
                </span>
                <span className="figures">{countLabel(item.count, TIMES_FORMS)}</span>
              </li>
            ))}
          </ul>
        ) : null}
        {clean.length > 0 ? (
          <Disclosure summary={`${scoped ? 'Без порушень у вибраних групах' : 'Без порушень'}: ${countLabel(clean.length, CONSTRAINT_FORMS)}`}>
            <ul className="flex flex-col gap-1 ps-5.5 text-caption text-secondary">
              {clean.map((constraint) => (
                <li key={constraint}>{constraint}</li>
              ))}
            </ul>
          </Disclosure>
        ) : null}
      </section>
      {achieved.length > 0 ? (
        <section aria-labelledby="achieved-title" className="flex flex-col gap-2">
          <h3 id="achieved-title" className="text-heading font-bold text-primary">
            Що вдалося
          </h3>
          <ul className="flex flex-col divide-y divide-[var(--border-color-separator)] border-y border-separator">
            {achieved.map((item) => (
              <li key={item.key} className="flex items-baseline gap-3 py-2">
                <span aria-hidden="true" className="size-2 shrink-0 translate-y-[-1px] rounded-full bg-[var(--fill-add)]" />
                <span className="min-w-0 flex-1 text-ui text-primary">{item.constraint}</span>
                <span className="text-caption text-secondary figures">{countLabel(item.count, TIMES_FORMS)}</span>
              </li>
            ))}
          </ul>
        </section>
      ) : null}
    </div>
  );
}

/** «4 → 2» з кольором напрямку; невідоме «Було» — лише «Стало». */
function BeforeAfter({ before, after }: { before: number | null; after: number | null }) {
  if (after === null) return <span className="text-muted">—</span>;
  if (before === null || before === after) {
    return <span className={cn(before === null ? 'text-primary' : 'text-secondary')}>{after}</span>;
  }
  return (
    <span className="inline-flex items-baseline gap-1">
      <span className="text-secondary">{before}</span>
      <span aria-hidden="true" className="text-muted">
        →
      </span>
      <span className="sr-only">стало</span>
      <span className={cn('font-semibold', after < before ? 'text-add' : 'text-danger')}>{after}</span>
    </span>
  );
}

/** Скільки груп видно одразу; більше (усі групи коледжу) — у розгортці. */
const OPEN_GROUP_ROWS = 12;

/** Показники кожної групи області: було → стало і скільки її пар переставлено. */
export function GroupComparison({ rows }: { rows: readonly GroupComparisonRow[] }) {
  if (rows.length === 0) return null;
  const hasMoved = rows.some((row) => row.moved !== null);
  const table = (
    // relative: абсолютні sr-only підписи («стало», різниця) лишаються всередині прокрутки, інакше на 375 px
    // сторінка прокручується вбік
    <div className="relative -mx-1 overflow-x-auto px-1">
      <table className="w-full min-w-[34rem] border-separate border-spacing-0 text-ui">
        <caption className="sr-only">По групах: було → стало</caption>
        <thead>
          <tr className="text-caption text-secondary">
            <th scope="col" className="border-b border-separator py-1.5 pe-3 text-start font-[650]">
              Група
            </th>
            {GROUP_COLUMNS.map((column) => (
              <th key={column.key} scope="col" className="border-b border-separator px-2 py-1.5 text-end font-[650]">
                {column.label}
              </th>
            ))}
            {hasMoved ? (
              <th scope="col" className="border-b border-separator py-1.5 ps-2 text-end font-[650]">
                Переставлено
              </th>
            ) : null}
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.group.id}>
              <th scope="row" className="border-b border-separator py-2 pe-3 text-start font-[550] text-primary">
                {row.group.code}
              </th>
              {row.cells.map((cell) => (
                <td key={cell.key} className="border-b border-separator px-2 py-2 text-end figures">
                  <BeforeAfter before={cell.before} after={cell.after} />
                </td>
              ))}
              {hasMoved ? (
                <td className="border-b border-separator py-2 ps-2 text-end text-secondary figures">
                  {row.moved ? `${row.moved.moved} з ${row.moved.total}` : '—'}
                </td>
              ) : null}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
  // телефон: таблиця з пʼятьма колонками не вміщується, а прокрутку вбік ніхто не помічає — картка на групу,
  // кожен показник своїм рядком «було → стало»
  const cards = (
    <ul aria-label="По групах: картки" className="flex flex-col divide-y divide-[var(--border-color-separator)] border-y border-separator sm:hidden">
      {rows.map((row) => (
        <li key={row.group.id} className="flex flex-col gap-1.5 py-2.5">
          <p className="text-ui font-[650] text-primary">{row.group.code}</p>
          <dl className="grid grid-cols-[minmax(0,1fr)_auto] gap-x-3 gap-y-1 text-ui">
            {row.cells.map((cell) => (
              <Fragment key={cell.key}>
                <dt className="text-secondary">{GROUP_COLUMNS.find((column) => column.key === cell.key)?.label}</dt>
                <dd className="text-end figures">
                  <BeforeAfter before={cell.before} after={cell.after} />
                </dd>
              </Fragment>
            ))}
            {hasMoved ? (
              <>
                <dt className="text-secondary">Переставлено</dt>
                <dd className="text-end text-secondary figures">{row.moved ? `${row.moved.moved} з ${row.moved.total}` : '—'}</dd>
              </>
            ) : null}
          </dl>
        </li>
      ))}
    </ul>
  );
  const content = (
    <>
      {cards}
      <div className="hidden sm:block">{table}</div>
    </>
  );
  return (
    <section aria-label="По групах" className="flex flex-col gap-2">
      {rows.length > OPEN_GROUP_ROWS ? (
        <Disclosure summary={`По групах: ${rows.length}`}>{content}</Disclosure>
      ) : (
        <>
          <h3 className="text-heading font-bold text-primary">
            По групах
          </h3>
          {content}
        </>
      )}
    </section>
  );
}
