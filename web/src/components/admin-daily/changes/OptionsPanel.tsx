import { CalendarRange, ChevronDown, UserRoundSearch } from 'lucide-react';
import { useId, useState } from 'react';
import { useLessonOptions, type ChangeOption } from '@/api/admin-daily';
import type { ReasonCode, TeacherRef } from '@/api/types';
import { Button, EmptyState, ErrorState, LoadingRegion, Skeleton } from '@/components/ui';
import { useNow } from '@/hooks/useNow';
import { formatInstantTime } from '@/lib/date';
import { plural } from '@/lib/labels';
import {
  actionLabel,
  bandHeading,
  effectiveOptionKey,
  EMPTY_POOL_TEXT,
  isOpen,
  lessonApprovalMode,
  lessonIsOver,
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

const OPTION_FORMS = ['варіант', 'варіанти', 'варіантів'] as const;

/** Шапка колонки: яку пару вирішуємо, одним реченням — замість чого, коли й де. */
function LessonHeading({ entry, open, showTitle }: { entry: LessonEntry; open: boolean; showTitle: boolean }) {
  const { lesson } = entry.item;
  const what = `${lesson.group.code}${lesson.subgroup > 0 ? ` (${lesson.subgroup} п/г)` : ''}, ${lesson.pair} пара`;
  const where = [lesson.startsAt ? `о ${lesson.startsAt}` : null, lesson.room ? `ауд. ${lesson.room.name}` : null, lesson.online ? 'дистанційно' : null]
    .filter(Boolean)
    .join(', ');
  return (
    <header className="flex flex-col gap-1">
      {showTitle ? <h3 className="text-heading font-bold text-pretty text-primary">{open ? `Варіанти для ${what}` : what}</h3> : null}
      <p className="text-caption text-secondary">
        Замість <span className="font-semibold text-primary">{lesson.discipline.shortName}</span> · {entry.absence.teacher.shortName}
        {where ? <span className="figures"> {where}</span> : null}
      </p>
    </header>
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
  /** Явний вибір диспетчера; без нього обрано рекомендований (якщо є). */
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
  /** Заголовок «Варіанти для 4КІ1, 3 пара»; у шторці його несе заголовок шторки. */
  showTitle?: boolean;
}

/**
 * Колонка «Варіанти»: яку пару вирішуємо, ухвалене рішення (якщо є) і варіанти в порядку сервера.
 * Рекомендований перший — розгорнутий блок з єдиною головною дією; решта — назва й одне речення,
 * згруповані реченнями «Теж підходить», «З нюансами», «Крайній випадок». Видно три, решта — за «Ще…».
 */
export function OptionsPanel(props: OptionsPanelProps) {
  const { entry, onSelect, pickingKey, expanded } = props;
  const open = isOpen(entry.state);
  const lessonId = entry.item.lesson.id;
  const options = useLessonOptions(open ? lessonId : null);
  const [teacherDialog, setTeacherDialog] = useState(false);
  const [planDialog, setPlanDialog] = useState(false);
  const listId = useId();
  const { now } = useNow();
  // минулу пару core підтверджує одразу, без прохання: тоді й кнопки кажуть «Призначити»
  const approvalMode = lessonApprovalMode(props.approvalMode, lessonIsOver(entry.item.date, entry.item.lesson.endsAt, now));

  const list = options.data?.options ?? [];
  const past = options.data?.past ?? false;
  const declined = options.data?.declined ?? [];
  const shown = expanded ? list : list.slice(0, VISIBLE_OPTIONS);
  const hidden = list.length - shown.length;
  const heading = optionsHeading(list, past);
  const selectedKey = effectiveOptionKey(list, props.selectedKey);
  const recommended = Boolean(list[0]?.recommended);

  return (
    <div className="@container flex flex-col gap-5">
      <LessonHeading entry={entry} open={open} showTitle={props.showTitle ?? true} />

      {entry.change && entry.state !== 'uncovered' ? (
        <DecisionCard
          change={entry.change}
          state={entry.state}
          lessonEndsAt={entry.item.lesson.endsAt}
          busy={props.decisionBusy}
          onResend={props.onResend}
          onConfirm={props.onConfirm}
          onCancel={props.onCancelDecision}
        />
      ) : null}

      {open ? (
        <section aria-label="Варіанти рішення" className="flex flex-col gap-3">
          {options.isPending ? (
            <LoadingRegion label="Підбираємо варіанти…">
              <div className="flex flex-col gap-2">
                <Skeleton className="h-28 w-full rounded-popover" />
                <Skeleton className="h-14 w-full rounded-popover" />
                <Skeleton className="h-14 w-full rounded-popover" />
              </div>
            </LoadingRegion>
          ) : options.isError ? (
            <ErrorState error={options.error} onRetry={() => void options.refetch()} size="sm" />
          ) : (
            <>
              {heading ? <p className="text-ui text-secondary">{heading}</p> : null}
              {declined.length > 0 ? (
                <p className="text-caption text-muted">
                  Відмовились: {declined.map((d) => `${d.teacher.shortName} (${formatInstantTime(d.at)})`).join(', ')}
                </p>
              ) : null}
              {poolIsEmpty(list) ? <p className="text-caption text-secondary">{EMPTY_POOL_TEXT}</p> : null}
              {list.length === 0 ? (
                <EmptyState title="Сервер не запропонував жодного варіанта." description="Призначте викладача вручну." size="sm" />
              ) : (
                <ol id={`${listId}-list`} className="-mx-1.5 flex flex-col gap-1" aria-label="Варіанти">
                  {shown.map((option, index) => {
                    const key = optionKey(option);
                    const best = index === 0 && recommended;
                    // підзаголовок — коли змінюється смуга; перший рядок без рекомендації — без нього (є речення вище)
                    const label = bandHeading(option.band);
                    const previous = index === 0 ? null : index === 1 && recommended ? 'best' : bandHeading(shown[index - 1]?.band ?? 'CLEAN');
                    const showHeading = !best && (index === 0 ? label !== 'Теж підходить' : label !== previous);
                    const selected = selectedKey === key;
                    return (
                      <li key={key} className="flex flex-col">
                        {showHeading ? <p className="px-3.5 pt-3 pb-1 text-ui font-[650] text-secondary">{label}</p> : null}
                        <OptionRow
                          option={option}
                          selectId={optionButtonId(lessonId, index)}
                          hotkey={index < 9 ? index + 1 : null}
                          selected={selected}
                          best={best}
                          busy={pickingKey === key}
                          disabled={pickingKey !== null}
                          actionLabel={actionLabel(option, approvalMode)}
                          onSelect={onSelect}
                          onAct={props.onAct}
                          actionExtra={
                            <ReasonPicker
                              collapsible
                              code={props.reasonCode}
                              text={props.reasonText}
                              onCodeChange={props.onReasonCodeChange}
                              onTextChange={props.onReasonTextChange}
                            />
                          }
                        />
                      </li>
                    );
                  })}
                </ol>
              )}
              <div className="flex flex-wrap items-center gap-x-1 gap-y-1">
                {hidden > 0 || expanded ? (
                  <Button
                    variant="ghost"
                    size="sm"
                    aria-expanded={expanded}
                    aria-controls={`${listId}-list`}
                    onClick={() => props.onExpandedChange(!expanded)}
                    iconEnd={<ChevronDown aria-hidden="true" className={expanded ? 'size-4 rotate-180' : 'size-4'} />}
                  >
                    {expanded ? 'Менше варіантів' : `Ще ${hidden} ${plural(hidden, OPTION_FORMS)}`}
                  </Button>
                ) : null}
                <Button variant="ghost" size="sm" onClick={() => setTeacherDialog(true)} iconStart={<UserRoundSearch aria-hidden="true" className="size-4" />}>
                  Інший викладач…
                </Button>
                {props.planCount >= 2 ? (
                  <Button variant="ghost" size="sm" onClick={() => setPlanDialog(true)} iconStart={<CalendarRange aria-hidden="true" className="size-4" />}>
                    Запропонувати план дня
                  </Button>
                ) : null}
              </div>
            </>
          )}

          <OtherTeacherDialog
            open={teacherDialog}
            onOpenChange={setTeacherDialog}
            lessonId={lessonId}
            pair={entry.item.lesson.pair}
            approvalMode={approvalMode}
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
