import { useCallback, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router';
import { useLessonOptions, type ChangeOption } from '@/api/admin-daily';
import type { ReasonCode, Uuid } from '@/api/types';
import { useElementRegistry } from '@/hooks/useElementRegistry';
import {
  effectiveOptionKey,
  isOpen,
  lessonButtonId,
  LESSON_STATE,
  optionButtonId,
  optionKey,
  reasonForOption,
  VISIBLE_OPTIONS,
  type LessonEntry,
} from '../model';
import type { ChangesWorkspace, Period } from './useChangesWorkspace';

export type ParamsPatch = Record<string, string | null>;

/** Стан екрана в адресі: період, обраний відсутній і пара (адресу можна надіслати колезі чи оновити сторінку). */
export function useChangesParams() {
  const [params, setParams] = useSearchParams();
  const period: Period = params.get('period') === 'week' ? 'week' : 'day';

  const updateParams = useCallback(
    (patch: ParamsPatch) => {
      setParams(
        (prev) => {
          const next = new URLSearchParams(prev);
          for (const [key, value] of Object.entries(patch)) {
            if (value === null) next.delete(key);
            else next.set(key, value);
          }
          return next;
        },
        { replace: true },
      );
    },
    [setParams],
  );

  return { params, period, absenceParam: params.get('absence'), lessonParam: params.get('lesson'), updateParams };
}

/** Причина в розпорядженні для обраного варіанта: власна правка диспетчера або типова за видом варіанта. */
export interface ReasonChoice {
  code: ReasonCode;
  text: string;
}

interface SelectionInput {
  workspace: ChangesWorkspace;
  /** Скільки колонок поміщається: з двох і більше щось обрано завжди, на телефоні — лише явний вибір. */
  panes: number;
  absenceParam: string | null;
  lessonParam: string | null;
  updateParams: (patch: ParamsPatch) => void;
}

/**
 * Що обрано на екрані замін: відсутній → пара → варіант, причина і розгорнутий список варіантів. Відсутній і
 * пара живуть в адресі, варіант і причина — лише для своєї пари (інша пара починає з чистого вибору).
 * Тут же J/K і 1–9: вибір з переведенням фокуса на рядок.
 */
export function useChangesSelection({ workspace, panes, absenceParam, lessonParam, updateParams }: SelectionInput) {
  const lessonRefs = useElementRegistry();
  const optionRefs = useElementRegistry();
  // причину диспетчер може змінити сам (code), інакше вона йде за видом обраного варіанта
  const [reason, setReason] = useState<{ lessonId: string | null; code: ReasonCode | null; text: string }>({
    lessonId: null,
    code: null,
    text: '',
  });
  // обраний варіант і розгорнутий список — лише для своєї пари: інша пара починає з чистого вибору
  const [selection, setSelection] = useState<{ lessonId: Uuid; key: string } | null>(null);
  const [expandedFor, setExpandedFor] = useState<Uuid | null>(null);

  const explicit = lessonParam ? (workspace.entries.find((e) => e.item.lesson.id === lessonParam) ?? null) : null;
  // відсутній: з адреси → відсутній обраної пари → на широкому екрані перший, у кого є що вирішувати
  const explicitAbsence = workspace.absences.find((a) => a.id === absenceParam) ?? explicit?.absence ?? null;
  // поки пари їдуть, не вгадуємо: інакше виділення перескочило б з першого на того, у кого є що вирішувати
  const fallbackAbsence =
    panes > 1 && !workspace.loading
      ? (workspace.entries.find((e) => LESSON_STATE[e.state].needsAction)?.absence ?? workspace.absences[0] ?? null)
      : null;
  const selectedAbsence = explicitAbsence ?? fallbackAbsence;
  const selectedAbsenceId = selectedAbsence?.id ?? null;
  const group = workspace.groups.find((g) => g.absence.id === selectedAbsenceId) ?? null;
  const visible = selectedAbsenceId ? workspace.entries.filter((entry) => entry.absence.id === selectedAbsenceId) : workspace.entries;

  // явно обрана пара з адреси; на широкому екрані без вибору — перша, що чекає рішення
  const fallback = panes > 1 ? (visible.find((e) => LESSON_STATE[e.state].needsAction) ?? visible[0] ?? null) : null;
  const selected = explicit ?? fallback;
  const selectedKey = selected ? lessonButtonId(selected) : null;
  const selectedLessonId = selected?.item.lesson.id ?? null;
  const options = useLessonOptions(selected && isOpen(selected.state) ? selected.item.lesson.id : null);
  const optionList = useMemo(() => options.data?.options ?? [], [options.data]);
  const explicitOptionKey = selection && selection.lessonId === selectedLessonId ? selection.key : null;
  const selectedOptionKey = effectiveOptionKey(optionList, explicitOptionKey);
  const selectedOption = selectedOptionKey ? (optionList.find((o) => optionKey(o) === selectedOptionKey) ?? null) : null;
  const ownReason = selected && reason.lessonId === selectedLessonId ? reason : null;
  const reasonFor = (option: ChangeOption | null): ReasonChoice => ({
    code: ownReason?.code ?? reasonForOption(option?.kind, selected?.absence.reasonCategory),
    text: ownReason?.text ?? '',
  });
  const reasonForSelected = reasonFor(selectedOption);
  const expanded = expandedFor !== null && expandedFor === selectedLessonId;
  // план дня: скільки пар цього відсутнього того ж дня ще чекають рішення
  const planCount = selected
    ? workspace.entries.filter((e) => e.absence.id === selected.absence.id && e.item.date === selected.item.date && isOpen(e.state)).length
    : 0;

  const { focusLater: focusLesson } = lessonRefs;
  const selectEntry = useCallback(
    (entry: LessonEntry, focus = false) => {
      // пара іншого відсутнього (J/K на межі списку) — перемикаємо й відсутнього
      updateParams({ lesson: entry.item.lesson.id, absence: entry.absence.id });
      if (focus) focusLesson(lessonButtonId(entry));
    },
    [updateParams, focusLesson],
  );

  const selectAbsence = (id: Uuid | null) => updateParams({ absence: id, lesson: null });

  /** J/K — наступна чи попередня пара; на межі списку переходить до пар сусіднього відсутнього. */
  const move = (step: 1 | -1) => {
    // у тому ж порядку, що й на екрані: відсутній за відсутнім, усередині — за датою
    const all = workspace.groups.flatMap((g) => g.days.flatMap((d) => d.entries));
    if (all.length === 0) return;
    const index = selected ? all.indexOf(selected) : -1;
    const next = all[Math.min(all.length - 1, Math.max(0, index + step))];
    if (next) selectEntry(next, true);
  };

  const selectOption = (option: ChangeOption) => {
    if (!selected) return;
    setSelection({ lessonId: selected.item.lesson.id, key: optionKey(option) });
  };

  /** 1–9 лише обирають варіант (і переводять на нього фокус); дія — Enter чи кнопка в рядку. */
  const selectNumber = (n: number) => {
    if (!selected || !isOpen(selected.state)) return;
    const option = optionList[n - 1];
    if (!option) return;
    if (n > VISIBLE_OPTIONS) setExpandedFor(selected.item.lesson.id);
    selectOption(option);
    optionRefs.focusLater(optionButtonId(selected.item.lesson.id, n - 1));
  };

  const changeReason = (patch: Partial<ReasonChoice>) => {
    if (!selected) return;
    setReason({ lessonId: selected.item.lesson.id, ...reasonForSelected, ...patch });
  };

  const setExpanded = (next: boolean) => setExpandedFor(next && selected ? selected.item.lesson.id : null);

  return {
    explicit,
    selectedAbsence,
    selectedAbsenceId,
    group,
    selected,
    selectedKey,
    explicitOptionKey,
    reasonFor,
    reasonForSelected,
    changeReason,
    expanded,
    setExpanded,
    planCount,
    selectEntry,
    selectAbsence,
    move,
    selectOption,
    selectNumber,
    clearOptionSelection: () => setSelection(null),
    registerLesson: lessonRefs.register,
    registerOption: optionRefs.register,
  };
}

export type ChangesSelection = ReturnType<typeof useChangesSelection>;
