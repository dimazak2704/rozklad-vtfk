import { DoorOpen, MonitorSmartphone } from 'lucide-react';
import { useMemo } from 'react';
import { Tabs as RadixTabs } from 'radix-ui';
import { useLessonOptions, type ChangeOption } from '@/api/admin-daily';
import type { ReasonCode } from '@/api/types';
import { EmptyState, ErrorState, LoadingRegion, Plate, Skeleton, Tabs, TabsContent } from '@/components/ui';
import { cn } from '@/lib/cn';
import { formatWeekdayDate } from '@/lib/date';
import { lineAttr } from '@/lib/discipline-color';
import { ABSENCE_REASON_LABEL, subgroupLabel } from '@/lib/labels';
import { lessonTime } from '@/lib/schedule';
import { absencePeriod } from '../format';
import { isOpen, OPTION_TABS, optionKey, optionsByKind, type LessonEntry, type OptionTab } from '../model';
import { DecisionCard } from './DecisionCard';
import { ReasonPicker } from './ReasonPicker';
import { OptionRow } from './OptionRow';


/** Шапка панелі: яку пару вирішуємо — час, група, дисципліна, аудиторія, хто відсутній. */
function LessonSummary({ entry }: { entry: LessonEntry }) {
  const { lesson, date } = entry.item;
  const subgroup = subgroupLabel(lesson.subgroup);
  return (
    <div className="grid grid-cols-[3rem_minmax(0,1fr)] gap-x-3">
      <div className="flex flex-col items-center leading-none">
        <span className="font-condensed text-headline font-bold text-primary figures">{lesson.pair}</span>
        <span className="mt-1 text-meta text-muted">пара</span>
      </div>
      <div className="flex min-w-0 flex-col gap-1">
        <p className="text-caption text-secondary figures">
          {formatWeekdayDate(date)}
          {lessonTime(lesson) ? ` · ${lessonTime(lesson)}` : ''}
        </p>
        <p className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1">
          <Plate>{lesson.group.code}</Plate>
          {subgroup ? <span className="text-caption text-secondary">{subgroup}</span> : null}
          <span {...lineAttr(lesson.discipline)} aria-hidden="true" className="size-2.5 shrink-0 rounded-full bg-[var(--line-dot)]" />
          <span className="font-condensed text-heading leading-tight font-bold text-primary">{lesson.discipline.shortName}</span>
        </p>
        {lesson.discipline.name !== lesson.discipline.shortName ? (
          <p className="text-caption text-secondary">{lesson.discipline.name}</p>
        ) : null}
        <p className="flex flex-wrap items-center gap-x-3 gap-y-0.5 text-caption text-secondary">
          {lesson.room ? (
            <span className="inline-flex items-center gap-1">
              <DoorOpen aria-hidden="true" className="size-3.5 text-muted" />
              ауд. {lesson.room.name}
            </span>
          ) : null}
          {lesson.online ? (
            <span className="inline-flex items-center gap-1">
              <MonitorSmartphone aria-hidden="true" className="size-3.5 text-muted" />
              дистанційно
            </span>
          ) : null}
        </p>
        <p className="text-caption text-muted">
          <span className="text-secondary line-through decoration-[var(--text-decoration-color-muted)]">{entry.absence.teacher.shortName}</span>
          {' — '}
          {ABSENCE_REASON_LABEL[entry.absence.reasonCategory].toLocaleLowerCase('uk')}, {absencePeriod(entry.absence)}
        </p>
      </div>
    </div>
  );
}

interface OptionsPanelProps {
  entry: LessonEntry;
  tab: OptionTab;
  onTabChange: (tab: OptionTab) => void;
  reasonCode: ReasonCode;
  reasonText: string;
  onReasonCodeChange: (code: ReasonCode) => void;
  onReasonTextChange: (text: string) => void;
  pickingKey: string | null;
  onPick: (option: ChangeOption) => void;
  decisionBusy: 'resend' | 'confirm' | 'cancel' | null;
  onResend: () => void;
  onConfirm: () => void;
  onCancelDecision: () => void;
}

/**
 * Права колонка: яку пару вирішуємо, ухвалене рішення (якщо є) і варіанти вкладками — викладачі,
 * інша дисципліна, перестановка, скасування, додаткова пара. Клавіші 1–9 обирають варіант зі списку.
 */
export function OptionsPanel(props: OptionsPanelProps) {
  const { entry, tab, onTabChange, pickingKey, onPick } = props;
  const open = isOpen(entry.state);
  const options = useLessonOptions(open ? entry.item.lesson.id : null);
  const grouped = useMemo(() => optionsByKind(options.data?.options ?? []), [options.data]);

  return (
    <div className="@container flex flex-col gap-4">
      <LessonSummary entry={entry} />

      {entry.change && entry.state !== 'uncovered' ? (
        <div className="border-t border-separator pt-4">
          <DecisionCard
            change={entry.change}
            state={entry.state}
            busy={props.decisionBusy}
            onResend={props.onResend}
            onConfirm={props.onConfirm}
            onCancel={props.onCancelDecision}
          />
        </div>
      ) : null}

      {open ? (
        <div className="flex flex-col gap-3 border-t border-separator pt-4">
          <ReasonPicker
            code={props.reasonCode}
            text={props.reasonText}
            onCodeChange={props.onReasonCodeChange}
            onTextChange={props.onReasonTextChange}
          />
          {options.isPending ? (
            <LoadingRegion label="Підбираємо варіанти…">
              <div className="flex flex-col gap-2">
                <Skeleton className="h-10 w-full" />
                {[0, 1, 2, 3].map((key) => (
                  <Skeleton key={key} className="h-20 w-full" />
                ))}
              </div>
            </LoadingRegion>
          ) : options.isError ? (
            <ErrorState error={options.error} onRetry={() => void options.refetch()} size="sm" />
          ) : (
            <Tabs value={tab} onValueChange={(value) => onTabChange(value as OptionTab)}>
              {/* пʼять видів рішень не вміщаються в один ряд вкладок — «таблетки» переносяться на другий рядок */}
              <RadixTabs.List aria-label="Види рішень" className="flex flex-wrap gap-1.5">
                {OPTION_TABS.map((item) => {
                  const count = grouped.get(item.kind)?.length ?? 0;
                  return (
                    <RadixTabs.Trigger
                      key={item.kind}
                      value={item.kind}
                      className={cn(
                        'inline-flex h-8 items-center gap-1.5 rounded-full border px-3 text-ui font-semibold whitespace-nowrap',
                        'transition-[background-color,border-color,color] duration-100 ease-out',
                        'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus',
                        'data-[state=active]:border-accent data-[state=active]:bg-selected data-[state=active]:text-accent',
                        'data-[state=inactive]:border-default data-[state=inactive]:bg-surface data-[state=inactive]:hover:border-strong',
                        count === 0 ? 'data-[state=inactive]:text-muted' : 'data-[state=inactive]:text-secondary',
                      )}
                    >
                      {item.label}
                      <span className="text-caption figures opacity-80">{count}</span>
                    </RadixTabs.Trigger>
                  );
                })}
              </RadixTabs.List>
              {OPTION_TABS.map((item) => {
                const list = grouped.get(item.kind) ?? [];
                return (
                  <TabsContent key={item.kind} value={item.kind}>
                    {list.length === 0 ? (
                      <EmptyState title="Немає варіантів" description={item.empty} size="sm" align="start" />
                    ) : (
                      <ol className="flex flex-col gap-2" aria-label={item.label}>
                        {list.map((option, index) => (
                          <OptionRow
                            key={optionKey(option)}
                            option={option}
                            rank={index + 1}
                            best={index === 0}
                            busy={pickingKey === optionKey(option)}
                            disabled={pickingKey !== null}
                            onPick={onPick}
                          />
                        ))}
                      </ol>
                    )}
                  </TabsContent>
                );
              })}
            </Tabs>
          )}
        </div>
      ) : null}
    </div>
  );
}
