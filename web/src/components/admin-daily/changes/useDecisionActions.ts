import { useCallback, useRef, useState } from 'react';
import {
  useCancelChange,
  useConfirmChange,
  useCreateChange,
  useCreateChangesBatch,
  useResendConfirmation,
  type ChangeOption,
} from '@/api/admin-daily';
import { api } from '@/api/client';
import { isApiError } from '@/api/errors';
import { qk } from '@/api/queryKeys';
import type { AbsenceDto, ChangeDto, CreateChangeRequest, ReasonCode, TeacherRef, Uuid } from '@/api/types';
import { notify } from '@/components/ui';
import { track, trackFinal } from '@/lib/undo/actions';
import { JournalBlockedError } from '@/lib/undo/errors';
import { countLabel } from '@/lib/labels';
import { confirm } from '@/stores/confirm';
import { manualTeacherRequest, optionKey, reasonForOption, requestForOption, type LessonEntry, type PickContext, type PlanChoice } from '../model';

/** Скільки часу вважати подію SSE «своєю» (щоб не показувати тост про власну дію вдруге). */
const OWN_ACTION_WINDOW_MS = 8_000;
/** Ключ ручного призначення в стані «призначаємо…». */
export const MANUAL_KEY = 'manual';
export const PLAN_KEY = 'plan';
const DECISION_FORMS = ['рішення', 'рішення', 'рішень'] as const;

function lessonLabel(entry: LessonEntry): string {
  const { lesson } = entry.item;
  return `${lesson.group.code}, ${lesson.pair} пара`;
}

/**
 * Речення про рішення для тосту й журналу: прохання пішло (CONFIRM) чи призначено одразу (інші режими).
 * «Призначено Коваль — 4КІ1, 2 пара».
 */
function decidedMessage(created: ChangeDto, where: string): string {
  if (created.kind === 'CANCEL') return `Знято пару — ${where}`;
  const who = created.new.teacher?.shortName ?? created.new.discipline?.shortName ?? 'зміну';
  const what = created.kind === 'MERGE_SUBGROUPS' ? `${who} на обидві підгрупи` : who;
  if (created.status === 'PENDING_CONFIRMATION') return `Надіслано прохання в Telegram: ${what} — ${where}`;
  if (created.kind === 'SWAP') return `Переставлено пари — ${where}`;
  return `Призначено ${what} — ${where}`;
}

/** Хто вже дізнався про рішення поза системою: тоді скасування теж піде йому повідомленням. */
function sideEffect(created: readonly ChangeDto[]): string | undefined {
  const told = created.some((c) => c.status === 'PENDING_CONFIRMATION' || c.status === 'CONFIRMED' || c.approvalMode === 'NOTIFY');
  return told ? 'Якщо скасувати, бот повідомить викладача про скасування.' : undefined;
}

/** Чому рішення вже не скасувати з журналу: зміна пішла далі, ніж була після рішення. */
export function movedReason(change: Pick<ChangeDto, 'status'>): string {
  switch (change.status) {
    case 'CONFIRMED':
      return 'Викладач уже підтвердив — скасуйте рішення вручну.';
    case 'DECLINED':
      return 'Викладач уже відмовився — пара знову чекає рішення.';
    case 'APPLIED':
      return 'Заміну вже внесено в розпорядження.';
    case 'CANCELLED':
      return 'Рішення вже скасовано.';
    case 'DRAFT':
    case 'PENDING_CONFIRMATION':
      return 'Стан рішення змінився після вас. Перевірте пару й вирішіть вручну.';
  }
}

function conflictText(error: unknown): string | null {
  if (!isApiError(error)) return null;
  if (error.conflicts.length > 0) return error.conflicts.map((c) => c.message).join('; ');
  if (error.errors.length > 0) return error.errors.map((e) => e.message).join('; ');
  return null;
}

export interface ReasonInput {
  code: ReasonCode;
  text: string;
}

function contextOf(entry: LessonEntry, reason: ReasonInput): PickContext {
  return {
    date: entry.item.date,
    lesson: entry.item.lesson,
    absenceId: entry.absence.id,
    reasonCode: reason.code,
    reasonText: reason.text.trim() || null,
  };
}

/**
 * Дії диспетчера над парою: виконати обраний варіант чи ручне призначення (з «Скасувати» в тості), план дня
 * однією транзакцією, нагадати, підтвердити, скасувати рішення. Запамʼятовує власні дії, щоб живі оновлення
 * не дублювали тости.
 */
export function useDecisionActions() {
  const create = useCreateChange();
  const createBatch = useCreateChangesBatch();
  const cancel = useCancelChange();
  const resend = useResendConfirmation();
  const confirmChange = useConfirmChange();
  const [pickingKey, setPickingKey] = useState<string | null>(null);
  const [busy, setBusy] = useState<'resend' | 'confirm' | 'cancel' | null>(null);
  const own = useRef(new Map<Uuid, number>());

  const markOwn = useCallback((id: Uuid) => {
    own.current.set(id, Date.now());
  }, []);

  const isOwn = useCallback((id: Uuid) => {
    const at = own.current.get(id);
    return at !== undefined && Date.now() - at < OWN_ACTION_WINDOW_MS;
  }, []);

  /**
   * Рішення — у журнал дій: «Скасувати» в тості чи Ctrl+Z скасовують створені зміни (пари знову чекають
   * рішення). Лише поки зміни не внесено в опубліковане розпорядження — далі сервер відповість 422, і
   * журнал так і скаже. Повтору немає: він надіслав би викладачу друге повідомлення.
   */
  const offerUndo = useCallback(
    (message: string, created: readonly ChangeDto[]) => {
      track({
        label: message,
        note: sideEffect(created),
        invalidate: [qk.admin.all],
        undo: async () => {
          // спершу свіжий стан: якщо викладач уже відповів чи зміну внесли в розпорядження,
          // наосліп не скасовуємо — це робиться вручну, з підтвердженням
          const fresh = await Promise.all(created.map((change) => api.get<ChangeDto>(`/admin/changes/${change.id}`)));
          const moved = fresh.find((change, index) => change.status !== created[index]?.status);
          if (moved) throw new JournalBlockedError(movedReason(moved), 'stale');
          for (const change of created) {
            markOwn(change.id);
            await cancel.mutateAsync(change.id);
          }
        },
      });
    },
    [cancel, markOwn],
  );

  /** Створює одну зміну; true — створено (діалог можна закрити). */
  const submit = useCallback(
    async (key: string, body: CreateChangeRequest | null, entry: LessonEntry, reason: ReasonInput): Promise<boolean> => {
      if (!body) {
        notify.error('Сервер не передав усіх даних для цього варіанта. Оновіть сторінку або оберіть інший.');
        return false;
      }
      if (reason.code === 'OTHER' && !reason.text.trim()) {
        notify.error('Для причини «Інше» коротко вкажіть її текст — він піде в колонку розпорядження.');
        return false;
      }
      setPickingKey(key);
      try {
        // створення не позначаємо «своїм»: відповідь викладача на цю заміну має показатися тостом
        const created = await create.mutateAsync(body);
        offerUndo(decidedMessage(created, lessonLabel(entry)), [created]);
        return true;
      } catch (error) {
        const conflicts = conflictText(error);
        if (conflicts && isApiError(error) && error.status === 409) {
          notify.error('Цей варіант уже недоступний', undefined, { description: conflicts });
        } else {
          notify.error(error, 'Не вдалося застосувати варіант');
        }
        return false;
      } finally {
        setPickingKey(null);
      }
    },
    [create, offerUndo],
  );

  const pick = useCallback(
    (option: ChangeOption, entry: LessonEntry, reason: ReasonInput) =>
      submit(optionKey(option), requestForOption(option, contextOf(entry, reason)), entry, reason),
    [submit],
  );

  /** «Інший викладач…»: та сама дисципліна, інший викладач — ручний виняток. */
  const assignManual = useCallback(
    (teacher: TeacherRef, entry: LessonEntry, reason: ReasonInput) =>
      submit(MANUAL_KEY, manualTeacherRequest(teacher.id, contextOf(entry, reason)), entry, reason),
    [submit],
  );

  /**
   * План дня: усі обрані рішення однією транзакцією (будь-який конфлікт — не створено нічого).
   * Причина кожної пари — за видом варіанта (DOMAIN §7), бо окремого вибору причини в плані немає.
   */
  const applyPlan = useCallback(
    async (choices: readonly PlanChoice[], absence: AbsenceDto, date: string): Promise<boolean> => {
      const bodies = choices.map((choice) =>
        requestForOption(choice.option, {
          date,
          lesson: choice.lesson,
          absenceId: absence.id,
          reasonCode: reasonForOption(choice.option.kind, absence.reasonCategory),
          reasonText: null,
        }),
      );
      if (bodies.length === 0 || bodies.some((body) => body === null)) {
        notify.error('Сервер не передав усіх даних для плану. Оновіть сторінку й спробуйте ще раз.');
        return false;
      }
      setPickingKey(PLAN_KEY);
      try {
        const created = await createBatch.mutateAsync({ changes: bodies as CreateChangeRequest[] });
        const pending = created.filter((c) => c.status === 'PENDING_CONFIRMATION').length;
        const decided = countLabel(created.length, DECISION_FORMS);
        const message = pending > 0 ? `Застосовано план: ${decided}, прохань у Telegram — ${pending}` : `Застосовано план: ${decided}`;
        offerUndo(message, created);
        return true;
      } catch (error) {
        const details = conflictText(error);
        if (details) notify.error('План не застосовано — нічого не змінено', undefined, { description: details });
        else notify.error(error, 'План не застосовано — нічого не змінено');
        return false;
      } finally {
        setPickingKey(null);
      }
    },
    [createBatch, offerUndo],
  );

  const run = useCallback(
    async (kind: 'resend' | 'confirm', change: ChangeDto) => {
      setBusy(kind);
      markOwn(change.id);
      try {
        if (kind === 'resend') {
          await resend.mutateAsync(change.id);
          notify.success(`Нагадування надіслано: ${change.new.teacher?.shortName ?? 'викладачу'}`);
          trackFinal(`Надіслано нагадування в Telegram: ${change.new.teacher?.shortName ?? 'викладачу'}`, 'Повідомлення в Telegram не відкликається');
        } else {
          await confirmChange.mutateAsync(change.id);
          notify.success(`Заміну підтверджено: ${change.new.teacher?.shortName ?? change.group.code}`);
          trackFinal(`Підтверджено заміну: ${change.new.teacher?.shortName ?? change.group.code}`, 'Підтвердження не скасовується');
        }
      } catch (error) {
        notify.error(error, kind === 'resend' ? 'Не вдалося надіслати нагадування' : 'Не вдалося підтвердити заміну');
      } finally {
        setBusy(null);
      }
    },
    [confirmChange, markOwn, resend],
  );

  const cancelDecision = useCallback(
    async (change: ChangeDto, entry: LessonEntry) => {
      const teacher = change.new.teacher?.shortName;
      const ok = await confirm({
        title: 'Скасувати рішення?',
        description:
          change.status === 'PENDING_CONFIRMATION' || change.status === 'CONFIRMED'
            ? 'Пара знову чекатиме рішення. Якщо викладачу вже надіслали запит, бот повідомить про скасування.'
            : 'Пара знову чекатиме рішення.',
        objectName: `${lessonLabel(entry)}${teacher ? ` — ${teacher}` : ''}`,
        confirmLabel: 'Скасувати рішення',
        cancelLabel: 'Залишити',
        tone: 'danger',
      });
      if (!ok) return;
      setBusy('cancel');
      markOwn(change.id);
      try {
        await cancel.mutateAsync(change.id);
        notify.info(`Рішення скасовано: ${lessonLabel(entry)} знову без рішення`);
        trackFinal(`Скасовано рішення — ${lessonLabel(entry)}${teacher ? `, ${teacher}` : ''}`, 'Скасоване рішення не повертається: оберіть варіант знову');
      } catch (error) {
        notify.error(error, 'Не вдалося скасувати рішення');
      } finally {
        setBusy(null);
      }
    },
    [cancel, markOwn],
  );

  return {
    pick,
    assignManual,
    applyPlan,
    pickingKey,
    busy,
    resend: (change: ChangeDto) => void run('resend', change),
    confirm: (change: ChangeDto) => void run('confirm', change),
    cancelDecision: (change: ChangeDto, entry: LessonEntry) => void cancelDecision(change, entry),
    isOwn,
  };
}
