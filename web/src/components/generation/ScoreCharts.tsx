import { CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import type { GenerationProgressPoint } from '@/api/types';
import { cn } from '@/lib/cn';
import { formatSeconds, timeTicks } from './model';

interface ScoreChartsProps {
  progress: readonly GenerationProgressPoint[];
  /** Старий фіксований ліміт — права межа осі, щоб було видно, скільки лишилось; null — автоматичний режим. */
  limitSeconds: number | null;
  live: boolean;
  /**
   * Скільки задача тривала (або йде зараз): лінія тягнеться до цього моменту, а не обривається на останньому
   * покращенні — рівна ділянка й показує, що розклад перестав покращуватися.
   */
  endSeconds?: number;
}

interface Point {
  t: number;
  hard: number;
  soft: number;
}

/**
 * Хід розвʼязання двома графіками зі спільною віссю часу (не одна вісь на дві різні величини):
 * зверху — мʼякий штраф (вікна, рвані дні…), знизу — жорсткі порушення. Обидва «менше — краще».
 */
export function ScoreCharts({ progress, limitSeconds, live, endSeconds }: ScoreChartsProps) {
  // Timefold дає оцінки зі знаком мінус; показуємо штраф як додатне число, що падає до нуля
  // оцінки Timefold недодатні; модуль дає штраф без «-0»
  const data: Point[] = progress.map((p) => ({
    t: p.t,
    hard: Math.abs(p.hard),
    soft: Math.abs(p.soft),
  }));
  // остання оцінка тримається до зупинки (або до «зараз») — дотягуємо сходинку до цього часу
  const tail = data.at(-1);
  if (tail && endSeconds && endSeconds > tail.t + 0.5) data.push({ ...tail, t: endSeconds });
  const lastT = data.at(-1)?.t ?? 0;
  // поки йде — вісь до ліміту (видно, скільки лишилось); після кінця — до фактичного часу
  const domainEnd = Math.ceil(live ? Math.max(limitSeconds ?? 0, lastT, 10) : Math.max(lastT, 2));
  const ticks = timeTicks(domainEnd);
  const hardMax = Math.max(1, ...data.map((d) => d.hard));

  return (
    <figure className="flex flex-col gap-3" aria-label="Хід розвʼязання">
      <ChartBlock
        title="Мʼякий штраф"
        note="зважена сума порушень мʼяких правил: вікон, рваних днів, 4-х пар зміни тощо"
        data={data}
        dataKey="soft"
        domainEnd={domainEnd}
        ticks={ticks}
        height={190}
        live={live}
        lineClass="[&_.recharts-line-curve]:stroke-[var(--stroke-accent)] [&_.recharts-dot]:fill-[var(--fill-accent)]"
      />
      <ChartBlock
        title="Жорсткі порушення"
        note="накладки викладачів, груп, аудиторій; мета — 0"
        data={data}
        dataKey="hard"
        domainEnd={domainEnd}
        ticks={ticks}
        height={110}
        yMax={hardMax}
        live={live}
        lineClass="[&_.recharts-line-curve]:stroke-[var(--stroke-cancel)] [&_.recharts-dot]:fill-[var(--fill-cancel)]"
      />
      <figcaption className="sr-only">
        {data.length > 0
          ? `За ${formatSeconds(lastT)}: мʼякий штраф ${data[0]?.soft ?? 0} → ${data.at(-1)?.soft ?? 0}, жорсткі порушення ${data[0]?.hard ?? 0} → ${data.at(-1)?.hard ?? 0}.`
          : 'Даних ще немає.'}
      </figcaption>
    </figure>
  );
}

interface ChartBlockProps {
  title: string;
  note: string;
  data: Point[];
  dataKey: 'soft' | 'hard';
  domainEnd: number;
  ticks: number[];
  height: number;
  yMax?: number;
  live: boolean;
  lineClass: string;
}

function ChartBlock({ title, note, data, dataKey, domainEnd, ticks, height, yMax, live, lineClass }: ChartBlockProps) {
  const last = data.at(-1);
  return (
    <div className="flex flex-col gap-1">
      <div className="flex items-baseline justify-between gap-3">
        <p className="text-ui font-semibold text-primary">
          {title} <span className="text-caption font-normal text-muted">· {note}</span>
        </p>
        {last ? (
          <span className="text-heading font-semibold text-primary figures" aria-label={`${title} зараз: ${last[dataKey]}`}>
            {last[dataKey].toLocaleString('uk-UA')}
          </span>
        ) : null}
      </div>
      <div
        aria-hidden="true"
        className={cn(
          'text-muted',
          '[&_.recharts-cartesian-grid_line]:stroke-[var(--stroke-rule)]',
          '[&_.recharts-cartesian-axis-tick-value]:fill-[var(--text-color-muted)] [&_.recharts-cartesian-axis-tick-value]:text-[0.6875rem]',
          '[&_.recharts-cartesian-axis-line]:stroke-[var(--stroke-rule)] [&_.recharts-cartesian-axis-tick-line]:stroke-[var(--stroke-rule)]',
          '[&_.recharts-tooltip-cursor]:stroke-[var(--stroke-muted)]',
          lineClass,
        )}
        style={{ height }}
      >
        <ResponsiveContainer width="100%" height="100%">
          <LineChart data={data} margin={{ top: 6, right: 12, bottom: 0, left: 0 }}>
            <CartesianGrid vertical={false} strokeDasharray="3 4" />
            <XAxis
              dataKey="t"
              type="number"
              domain={[0, domainEnd]}
              ticks={ticks}
              interval="preserveStartEnd"
              tickFormatter={(value: number) => formatSeconds(value)}
              tickLine={false}
              minTickGap={24}
            />
            <YAxis
              width={52}
              tickLine={false}
              axisLine={false}
              allowDecimals={false}
              // жорсткі — від нуля (мета — 0); мʼякий штраф — у межах даних, щоб зміну було видно
              domain={yMax !== undefined ? [0, yMax] : ['auto', 'auto']}
              tickFormatter={(value: number) => value.toLocaleString('uk-UA')}
            />
            <Tooltip
              content={({ active, payload }) => <ScoreTooltip active={active} payload={payload} title={title} dataKey={dataKey} />}
              isAnimationActive={false}
            />
            <Line
              type="stepAfter"
              dataKey={dataKey}
              strokeWidth={2}
              dot={false}
              activeDot={{ r: 4, strokeWidth: 2 }}
              isAnimationActive={false}
              className={live ? 'recharts-live' : undefined}
            />
          </LineChart>
        </ResponsiveContainer>
      </div>
    </div>
  );
}

interface ScoreTooltipProps {
  active?: boolean;
  payload?: ReadonlyArray<{ payload?: unknown }>;
  title: string;
  dataKey: 'soft' | 'hard';
}

function ScoreTooltip({ active, payload, title, dataKey }: ScoreTooltipProps) {
  const point = payload?.[0]?.payload as Point | undefined;
  if (!active || !point) return null;
  return (
    <div className="rounded-control border border-default bg-raised px-2.5 py-1.5 text-caption text-primary shadow-popover">
      <p className="text-meta text-muted figures">на {formatSeconds(point.t)}</p>
      <p className="figures">
        {title}: <span className="font-semibold">{point[dataKey].toLocaleString('uk-UA')}</span>
      </p>
    </div>
  );
}
