import { useMemo, useState, type FormEvent } from 'react';
import { useCancelChange, useCreateChange, useTeacherAvailability } from '@/api/admin-daily';
import { isApiError } from '@/api/errors';
import { useLoadItems } from '@/api/load';
import { useBells } from '@/api/meta';
import { usePublicGroups, usePublicSchedule, usePublicTeachers } from '@/api/public';
import { qk } from '@/api/queryKeys';
import type { ChangeDto, IsoDate, LessonView, ReasonCode, SemesterRef, Uuid } from '@/api/types';
import { notify } from '@/components/ui';
import { formatShortWeekdayDate, isIsoDate, nearestStudyDay } from '@/lib/date';
import { track } from '@/lib/undo/actions';
import { reasonForOption } from '../model';
import { useTeacherLabel } from '../useTeacherLabel';
import {
  defaultTeacherDiscipline,
  lessonSelectable,
  loadTeachers,
  newChangeLabel,
  newChangeRequest,
  pairsOfDay,
  teacherChangeKind,
  teacherDisciplines,
  validateNewChange,
  type NewChangeErrors,
  type NewChangeMode,
  type NewChangePrefill,
  type NewChangeValues,
} from './new-change';
import { useNewChangeOptions } from './useNewChangeOptions';

const NO_LESSONS: LessonView[] = [];

export interface NewChangeFormState {
  mode: NewChangeMode;
  groupId: Uuid | null;
  date: IsoDate;
  pairs: number[];
  disciplineId: Uuid | null;
  /** null — підставляємо з навантаження (додаткова пара) чи нікого (інший викладач). */
  teacherId: Uuid | null;
  /** null — уся група або підгрупа викладача з навантаження. */
  subgroup: number | null;
  roomId: Uuid | null;
  lessonId: Uuid | null;
  /** null — причина за видом зміни. */
  reasonCode: ReasonCode | null;
  reasonText: string;
}

function initialState(date: IsoDate, prefill: NewChangePrefill | null): NewChangeFormState {
  return {
    mode: prefill?.mode ?? 'additional',
    groupId: prefill?.groupId ?? null,
    // у вихідний пар немає: сторінку відкрили в неділю — форма пропонує понеділок
    date: prefill?.date ?? nearestStudyDay(date),
    pairs: [...(prefill?.pairs ?? [])],
    disciplineId: prefill?.disciplineId ?? null,
    teacherId: null,
    subgroup: null,
    roomId: prefill?.roomId ?? null,
    lessonId: prefill?.lessonId ?? null,
    reasonCode: null,
    reasonText: '',
  };
}

interface NewChangeFormInput {
  open: boolean;
  date: IsoDate;
  semester: SemesterRef | null;
  prefill: NewChangePrefill | null;
  onCreated?: (change: ChangeDto) => void;
  onOpenChange: (open: boolean) => void;
}

/**
 * Стан і дані форми «Нова зміна»: що обрано, що підставлено з навантаження й розкладу групи, помилки й
 * збереження (з «Скасувати» в журналі дій і прибиранням зміни з відмовою, яку нова заміняє).
 */
export function useNewChangeForm({ open, date, semester, prefill, onCreated, onOpenChange }: NewChangeFormInput) {
  const [state, setState] = useState<NewChangeFormState>(() => initialState(date, prefill));
  const [showErrors, setShowErrors] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const create = useCreateChange();
  const cancel = useCancelChange();
  const name = useTeacherLabel();

  const groups = usePublicGroups();
  const teachers = usePublicTeachers();
  const load = useLoadItems(open ? semester?.id : null, state.groupId);
  // набрана частково дата — ще не дата: ні дзвінків, ні розкладу за нею не просимо
  const validDate = isIsoDate(state.date);
  const bells = useBells(open && validDate ? state.date : null);
  // лише пари, що є цього дня за дзвінками (у пʼятницю 7–8 немає)
  const dayPairs = pairsOfDay(bells.data?.slots);
  const needsLesson = state.mode !== 'additional';
  // пари групи цього дня: для зміни пари — з чого обирати, для додаткової — які номери вже зайняті
  const schedule = usePublicSchedule('GROUP', state.groupId ?? undefined, state.date, state.date, { enabled: open && validDate && Boolean(state.groupId) });
  const dayLessons = useMemo(
    () => (schedule.data?.days ?? []).flatMap((day) => day.lessons).sort((a, b) => a.pair - b.pair || a.subgroup - b.subgroup),
    [schedule.data],
  );
  const lessons = needsLesson ? dayLessons : NO_LESSONS;
  const busyPairs = useMemo(() => {
    const out = new Map<number, string>();
    for (const l of dayLessons) {
      if (l.kind === 'CANCELLED') continue;
      const label = out.get(l.pair);
      out.set(l.pair, label && label !== l.discipline.shortName ? `${label}, ${l.discipline.shortName}` : l.discipline.shortName);
    }
    return out;
  }, [dayLessons]);

  // пара: обрана явно, а з «Обліку годин» («Вичитано») — перша пара цієї дисципліни в цей день
  const lesson =
    lessons.find((l) => l.id === state.lessonId) ??
    (state.lessonId === null && prefill?.disciplineId
      ? (lessons.find((l) => l.discipline.id === prefill.disciplineId && lessonSelectable(l, state.mode)) ?? null)
      : null);

  // додаткова пара: викладач і підгрупа — з навантаження, доки диспетчер не обрав сам
  const assigned = loadTeachers(load.data, state.groupId, state.disciplineId);
  // викладач, що вже відмовився від цієї пари, — не підставляємо його вдруге
  const declinedId = prefill?.replaces?.teacherId ?? null;
  const declinedTeacher = declinedId ? ((teachers.data ?? []).find((t) => t.id === declinedId) ?? null) : null;
  const loadSuggested = assigned.find((t) => t.subgroup === state.subgroup) ?? assigned[0] ?? null;
  const suggested = loadSuggested && loadSuggested.teacher.id !== declinedId ? loadSuggested : null;
  const own = teacherDisciplines(load.data, state.groupId, state.teacherId);

  const values: NewChangeValues =
    state.mode === 'additional'
      ? {
          mode: 'additional',
          groupId: state.groupId,
          date: state.date,
          // пара, якої в новий день немає, із запиту випадає
          pairs: state.pairs.filter((pair) => dayPairs.includes(pair)),
          subgroup: state.subgroup ?? loadSuggested?.subgroup ?? 0,
          disciplineId: state.disciplineId,
          teacherId: state.teacherId ?? suggested?.teacher.id ?? null,
          roomId: state.roomId,
          lesson: null,
          reasonCode: 'OTHER',
          reasonText: state.reasonText,
        }
      : {
          mode: state.mode,
          groupId: state.groupId,
          date: state.date,
          pairs: [],
          subgroup: lesson?.subgroup ?? 0,
          disciplineId: state.mode === 'teacher' ? (state.disciplineId ?? defaultTeacherDiscipline(own, lesson)) : null,
          teacherId: state.mode === 'teacher' ? state.teacherId : null,
          roomId: state.mode === 'finished' ? null : state.roomId,
          lesson,
          reasonCode: 'OTHER',
          reasonText: state.reasonText,
        };
  const teacherKind = state.mode === 'teacher' ? teacherChangeKind(values) : null;
  const reasonCode: ReasonCode = state.reasonCode ?? reasonForOption(teacherKind, undefined);
  values.reasonCode = state.mode === 'teacher' ? reasonCode : 'OTHER';
  const errors: NewChangeErrors = showErrors ? validateNewChange(values) : {};

  const availability = useTeacherAvailability(open && state.mode === 'teacher' && lesson ? lesson.id : null, '');
  const options = useNewChangeOptions({ open, groupId: state.groupId, groups: groups.data, teachers: teachers.data, load: load.data, availability: availability.data });

  const patch = (next: Partial<NewChangeFormState>) => {
    setFormError(null);
    setState((prev) => ({ ...prev, ...next }));
  };
  const setMode = (mode: NewChangeMode) =>
    patch({
      mode,
      lessonId: null,
      teacherId: null,
      roomId: null,
      reasonCode: null,
      disciplineId: mode === 'additional' || mode === 'finished' ? state.disciplineId : null,
    });

  const reset = () => {
    setState(initialState(date, prefill));
    setShowErrors(false);
    setFormError(null);
  };

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setShowErrors(true);
    const body = newChangeRequest(values);
    if (!body) return;
    setFormError(null);
    try {
      const created = await create.mutateAsync(body);
      const group = groups.data?.find((g) => g.id === created.group.id)?.code ?? created.group.code;
      const label = newChangeLabel(state.mode, group, { from: created.pairFrom, to: created.pairTo }, formatShortWeekdayDate(created.date));
      track({
        label: created.status === 'PENDING_CONFIRMATION' ? `${label}: надіслано прохання в Telegram` : label,
        note: created.status === 'PENDING_CONFIRMATION' ? 'Якщо скасувати, бот повідомить викладача про скасування.' : undefined,
        invalidate: [qk.admin.all],
        undo: () => cancel.mutateAsync(created.id),
      });
      // нова зміна заміняє ту, від якої відмовились: стару прибираємо, щоб у списку не висіла «відмова»
      const replaced = prefill?.replaces?.changeId;
      if (replaced) {
        try {
          await cancel.mutateAsync(replaced);
        } catch (error) {
          notify.error(error, 'Нову зміну збережено, але стару з відмовою прибрати не вдалося — приберіть її в «Інших змінах»');
        }
      }
      onCreated?.(created);
      onOpenChange(false);
      reset();
    } catch (error) {
      if (isApiError(error) && (error.conflicts.length > 0 || error.errors.length > 0)) {
        setFormError([...error.conflicts.map((c) => c.message), ...error.errors.map((e) => e.message)].join('; '));
      } else {
        notify.error(error, 'Не вдалося зберегти зміну');
      }
    }
  };

  const declinedName = declinedTeacher ? name(declinedTeacher) : null;

  return {
    state,
    patch,
    setMode,
    values,
    errors,
    reasonCode,
    formError,
    pending: create.isPending,
    submit,
    reset,
    // розклад і дзвінки дня
    slots: bells.data?.slots,
    dayPairs,
    busyPairs,
    lessons,
    lesson,
    scheduleLoading: schedule.isPending && Boolean(state.groupId),
    scheduleFailed: schedule.isError,
    // навантаження
    assigned,
    suggested,
    own,
    declinedName,
    declinedHint: declinedName ? `Відмова: ${declinedName} — оберіть іншого` : undefined,
    // списки вибору
    options,
    loading: {
      groups: groups.isPending,
      teachers: teachers.isPending,
      availability: availability.isPending && Boolean(lesson),
    },
  };
}

export type NewChangeForm = ReturnType<typeof useNewChangeForm>;
