import { ChevronDown, DoorOpen, MonitorSmartphone, UserRoundSearch } from 'lucide-react';
import { useId, useState } from 'react';
import { useLessonOptions, type ChangeOption } from '@/api/admin-daily';
import type { ReasonCode, TeacherRef } from '@/api/types';
import { Button, EmptyState, ErrorState, LoadingRegion, Plate, Skeleton } from '@/components/ui';
import { formatInstantTime, formatWeekdayDate } from '@/lib/date';
import { lineAttr } from '@/lib/discipline-color';
import { ABSENCE_REASON_LABEL, subgroupLabel } from '@/lib/labels';
import { lessonTime } from '@/lib/schedule';
import { absencePeriod } from '../format';
import {
  actionLabel,
  EMPTY_POOL_TEXT,
  isOpen,
  optionButtonId,
  optionKey,
  optionsHeading,
  poolIsEmpty,
  VISIBLE_OPTIONS,
  type LessonEntry,
  type PlanChoice,
} from '../model';
import { DayPlanDialog } from './DayPlanDialog';
import { DecisionCard } from './DecisionCard';
import { OptionRow } from './OptionRow';
import { OtherTeacherDialog } from './OtherTeacherDialog';
import { ReasonPicker } from './ReasonPicker';
import { MANUAL_KEY, PLAN_KEY } from './useDecisionActions';

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
  /** Режим погодження з налаштувань: CONFIRM — «Надіслати прохання», інакше «Призначити». */
  approvalMode: string | null | undefined;
  /** Причина, що піде в розпорядження (уже з урахуванням виду обраного варіанта). */
  reasonCode: ReasonCode;
  reasonText: string;
  onReasonCodeChange: (code: ReasonCode) => void;
  onReasonTextChange: (text: string) => void;
  selectedKey: string | null;
  onSelect: (option: ChangeOption) => void;
  expanded: boolean;
  onExpandedChange: (expanded: boolean) => void;
  pickingKey: string | null;
  onAct: (option: ChangeOption) => void;
  onAssignManual: (teacher: TeacherRef) => Promise<boolean>;
  /** Скільки пар цього відсутнього того ж дня ще без рішення (план дня — від двох). */
  planCount: number;
  onApplyPlan: (choices: PlanChoice[]) => Promise<boolean>;
  decisionBusy: 'resend' | 'confirm' | 'cancel' | null;
  onResend: () => void;
  onConfirm: () => void;
  onCancelDecision: () => void;
}

/**
 * Права колонка: яку пару вирішуємо, ухвалене рішення (якщо є) і варіанти одним списком у порядку сервера.
 * Видно три, решта — за «Ще варіанти». Клік чи 1–9 лише обирають; дія — кнопка в обраному рядку або Enter.
 * Ручний шлях — «Інший викладач…»; кілька пар дня — «Запропонувати план дня».
 */
export function OptionsPanel(props: OptionsPanelProps) {
  const { entry, selectedKey, onSelect, pickingKey, expanded } = props;
  const open = isOpen(entry.state);
  const lessonId = entry.item.lesson.id;
  const options = useLessonOptions(open ? lessonId : null);
  const [teacherDialog, setTeacherDialog] = useState(false);
  const [planDialog, setPlanDialog] = useState(false);
  const listId = useId();

  const list = options.data?.options ?? [];
  const past = options.data?.past ?? false;
  const declined = options.data?.declined ?? [];
  const shown = expanded ? list : list.slice(0, VISIBLE_OPTIONS);
  const hidden = list.length - shown.length;
  const heading = optionsHeading(list, past);

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
        <section aria-labelledby={`${listId}-title`} className="flex flex-col gap-3 border-t border-separator pt-4">
          <div className="flex flex-wrap items-center justify-between gap-x-3 gap-y-2">
            <h3 id={`${listId}-title`} className="text-ui font-semibold text-primary">
              Як закрити пару
            </h3>
            {props.planCount >= 2 ? (
              <Button variant="secondary" size="sm" onClick={() => setPlanDialog(true)}>
                Запропонувати план дня
              </Button>
            ) : null}
          </div>

          {options.isPending ? (
            <LoadingRegion label="Підбираємо варіанти…">
              <div className="flex flex-col gap-2">
                {[0, 1, 2].map((key) => (
                  <Skeleton key={key} className="h-20 w-full" />
                ))}
              </div>
            </LoadingRegion>
          ) : options.isError ? (
            <ErrorState error={options.error} onRetry={() => void options.refetch()} size="sm" />
          ) : (
            <>
              {heading ? <p className="text-caption text-secondary">{heading}</p> : null}
              {declined.length > 0 ? (
                <p className="text-caption text-secondary">
                  Відмовились: {declined.map((d) => `${d.teacher.shortName} (${formatInstantTime(d.at)})`).join(', ')}
                </p>
              ) : null}
              {poolIsEmpty(list) ? <p className="text-caption text-secondary">{EMPTY_POOL_TEXT}</p> : null}
              {list.length === 0 ? (
                <EmptyState title="Немає варіантів" description="Сервер не запропонував жодного рішення для цієї пари." size="sm" align="start" />
              ) : (
                <ol id={`${listId}-list`} className="flex flex-col gap-2" aria-label="Варіанти">
                  {shown.map((option, index) => {
                    const key = optionKey(option);
                    return (
                      <OptionRow
                        key={key}
                        option={option}
                        selectId={optionButtonId(lessonId, index)}
                        hotkey={index < 9 ? index + 1 : null}
                        selected={selectedKey === key}
                        busy={pickingKey === key}
                        disabled={pickingKey !== null}
                        actionLabel={actionLabel(option, props.approvalMode)}
                        onSelect={onSelect}
                        onAct={props.onAct}
                        actionExtra={
                          <ReasonPicker
                            code={props.reasonCode}
                            text={props.reasonText}
                            onCodeChange={props.onReasonCodeChange}
                            onTextChange={props.onReasonTextChange}
                          />
                        }
                      />
                    );
                  })}
                </ol>
              )}
              <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
                {hidden > 0 || expanded ? (
                  <Button
                    variant="ghost"
                    size="sm"
                    aria-expanded={expanded}
                    aria-controls={`${listId}-list`}
                    onClick={() => props.onExpandedChange(!expanded)}
                    iconEnd={<ChevronDown aria-hidden="true" className={expanded ? 'size-4 rotate-180' : 'size-4'} />}
                  >
                    {expanded ? 'Менше варіантів' : `Ще варіанти (${hidden})`}
                  </Button>
                ) : null}
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={() => setTeacherDialog(true)}
                  iconStart={<UserRoundSearch aria-hidden="true" className="size-4" />}
                >
                  Інший викладач…
                </Button>
              </div>
            </>
          )}

          <OtherTeacherDialog
            open={teacherDialog}
            onOpenChange={setTeacherDialog}
            lessonId={lessonId}
            pair={entry.item.lesson.pair}
            approvalMode={props.approvalMode}
            busy={pickingKey === MANUAL_KEY}
            onAssign={(teacher) => {
              void props.onAssignManual(teacher).then((done) => done && setTeacherDialog(false));
            }}
          />
          {props.planCount >= 2 ? (
            <DayPlanDialog
              open={planDialog}
              onOpenChange={setPlanDialog}
              absence={entry.absence}
              date={entry.item.date}
              approvalMode={props.approvalMode}
              busy={pickingKey === PLAN_KEY}
              onApply={(choices) => {
                void props.onApplyPlan(choices).then((done) => done && setPlanDialog(false));
              }}
            />
          ) : null}
        </section>
      ) : null}
    </div>
  );
}
