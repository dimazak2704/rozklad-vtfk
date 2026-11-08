import { useCallback, useRef, useState } from 'react';
import {
  useCancelChange,
  useConfirmChange,
  useCreateChange,
  useResendConfirmation,
  type ChangeOption,
} from '@/api/admin-daily';
import { isApiError } from '@/api/errors';
import type { ChangeDto, ReasonCode, Uuid } from '@/api/types';
import { notify } from '@/components/ui';
import { formatShortWeekdayDate } from '@/lib/date';
import { confirm } from '@/stores/confirm';
import { optionKey, requestsForOption, type LessonEntry } from '../model';

/** Скільки часу вважати подію SSE «своєю» (щоб не показувати тост про власну дію вдруге). */
const OWN_ACTION_WINDOW_MS = 8_000;

function lessonLabel(entry: LessonEntry): string {
  const { lesson } = entry.item;
  return `${lesson.group.code}, ${lesson.pair} пара`;
}

function pickedMessage(option: ChangeOption, created: ChangeDto, entry: LessonEntry): string {
  const where = lessonLabel(entry);
  const who = option.teacher?.shortName ?? option.title;
  const pending = created.status === 'PENDING_CONFIRMATION';
  switch (option.kind) {
    case 'CANCEL':
      return `Пари не буде: ${where}`;
    case 'ADDITIONAL':
      return `Пару скасовано, відпрацювання — ${option.date ? formatShortWeekdayDate(option.date) : ''}, ${option.pair ?? ''} пара`;
    case 'SWAP':
      return pending ? `Запит у Telegram надіслано: ${option.title}` : `Переставлено: ${option.title}`;
    case 'REPLACE_TEACHER':
    case 'REPLACE_DISCIPLINE':
    case 'ROOM_CHANGE':
    case 'BLOCK':
    case 'DISCIPLINE_FINISHED':
      return pending ? `Запит у Telegram надіслано: ${who} — ${where}` : `Заміну підтверджено: ${who} — ${where}`;
  }
}

/**
 * Дії диспетчера над парою: обрати варіант (з «Скасувати» в тості), нагадати, підтвердити, скасувати рішення.
 * Запамʼятовує власні дії, щоб живі оновлення не дублювали тости.
 */
export function useDecisionActions() {
  const create = useCreateChange();
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

  const pick = useCallback(
    async (option: ChangeOption, entry: LessonEntry, reason: { code: ReasonCode; text: string }) => {
      const requests = requestsForOption(option, {
        date: entry.item.date,
        lesson: entry.item.lesson,
        absenceId: entry.absence.id,
        reasonCode: reason.code,
        reasonText: reason.text.trim() || null,
      });
      if (!requests) {
        notify.error('Сервер не передав усіх даних для цього варіанта. Оновіть сторінку або оберіть інший.');
        return;
      }
      if (reason.code === 'OTHER' && !reason.text.trim()) {
        notify.error('Для причини «Інше» коротко вкажіть її текст — він піде в колонку розпорядження.');
        return;
      }
      setPickingKey(optionKey(option));
      const created: ChangeDto[] = [];
      try {
        for (const body of requests) {
          // створення не позначаємо «своїм»: відповідь викладача на цю заміну має показатися тостом
          created.push(await create.mutateAsync(body));
        }
        const first = created[0];
        if (first) {
          notify.undo(pickedMessage(option, first, entry), {
            undoLabel: 'Скасувати',
            onUndo: () => {
              void Promise.all(created.map((c) => cancel.mutateAsync(c.id))).then(
                () => notify.info('Вибір скасовано — пара знову чекає рішення'),
                (error: unknown) => notify.error(error, 'Не вдалося скасувати вибір'),
              );
            },
          });
        }
      } catch (error) {
        if (created.length > 0) {
          notify.error(error, 'Пару скасовано, але відпрацювання не створено');
        } else if (isApiError(error) && error.conflicts.length > 0) {
          notify.error(error.conflicts.map((c) => c.message).join('; '), 'Цей варіант уже недоступний');
        } else {
          notify.error(error, 'Не вдалося обрати варіант');
        }
      } finally {
        setPickingKey(null);
      }
    },
    [cancel, create],
  );

  const run = useCallback(
    async (kind: 'resend' | 'confirm', change: ChangeDto) => {
      setBusy(kind);
      markOwn(change.id);
      try {
        if (kind === 'resend') {
          await resend.mutateAsync(change.id);
          notify.success(`Нагадування надіслано: ${change.new.teacher?.shortName ?? 'викладачу'}`);
        } else {
          await confirmChange.mutateAsync(change.id);
          notify.success(`Заміну підтверджено: ${change.new.teacher?.shortName ?? change.group.code}`);
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
    pickingKey,
    busy,
    resend: (change: ChangeDto) => void run('resend', change),
    confirm: (change: ChangeDto) => void run('confirm', change),
    cancelDecision: (change: ChangeDto, entry: LessonEntry) => void cancelDecision(change, entry),
    isOwn,
  };
}
