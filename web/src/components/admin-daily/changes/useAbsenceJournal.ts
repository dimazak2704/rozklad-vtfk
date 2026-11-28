import { useCancelAbsence, useCancelChange, useUpdateAbsence } from '@/api/admin-daily';
import { qk } from '@/api/queryKeys';
import type { AbsenceDto, AbsenceRequest, ChangeDto } from '@/api/types';
import { notify } from '@/components/ui';
import { track, trackFinal } from '@/lib/undo/actions';
import { confirm } from '@/stores/confirm';
import { absencePeriod, decisionSummary } from '../format';
import { useTeacherLabel } from '../useTeacherLabel';
import { standaloneHeading } from './new-change';

/** Відсутність → тіло PUT (без версії: її додає журнал). */
function absenceRequest(absence: AbsenceDto): AbsenceRequest {
  return {
    teacherId: absence.teacher.id,
    dateFrom: absence.dateFrom,
    dateTo: absence.dateTo,
    pairs: absence.pairs,
    reasonCategory: absence.reasonCategory,
    comment: absence.comment,
  };
}

/**
 * Дії з відсутностями й окремими змінами на екрані замін, з підтвердженням і записом у журнал дій
 * («Скасувати» / «Повторити»). Скасування відсутності чи зміни остаточне: журнал лише фіксує його.
 */
export function useAbsenceJournal({ onAbsenceCancelled }: { onAbsenceCancelled: (absence: AbsenceDto) => void }) {
  const name = useTeacherLabel();
  const cancelAbsence = useCancelAbsence();
  const updateAbsence = useUpdateAbsence();
  const cancelChange = useCancelChange();

  /**
   * Відсутність — у журнал дій. Додану скасовує видалення (разом з її рішеннями, ще не внесеними в
   * розпорядження); змінену — попередні дати й пари з версією після правки. Рішення, які правка вже
   * скасувала, не повертаються — про це каже примітка.
   */
  const journalAbsence = (before: AbsenceDto | null, saved: AbsenceDto) => {
    const where = `${name(saved.teacher)}, ${absencePeriod(saved)}`;
    const invalidate = [qk.admin.all];
    if (!before) {
      track({
        label: `Додано відсутність — ${where}`,
        note: 'Скасування прибере й рішення за нею, ще не внесені в розпорядження.',
        invalidate,
        undo: () => cancelAbsence.mutateAsync(saved.id),
      });
      return;
    }
    let version = saved.version;
    const put = async (from: AbsenceDto) => {
      const result = await updateAbsence.mutateAsync({ id: saved.id, body: { ...absenceRequest(from), version } });
      version = result.version;
    };
    track({
      label: `Змінено відсутність — ${where}`,
      note: 'Рішення, які ця правка скасувала, після скасування не повернуться.',
      invalidate,
      undo: () => put(before),
      redo: () => put(saved),
    });
  };

  const cancelAbsenceFlow = async (absence: AbsenceDto) => {
    const ok = await confirm({
      title: 'Викладач на місці — скасувати відсутність?',
      description: 'Рішення за цією відсутністю, ще не внесені в розпорядження, теж скасуються.',
      objectName: `${name(absence.teacher)}, ${absencePeriod(absence)}`,
      confirmLabel: 'Скасувати відсутність',
      cancelLabel: 'Залишити',
      tone: 'danger',
    });
    if (!ok) return;
    try {
      await cancelAbsence.mutateAsync(absence.id);
      onAbsenceCancelled(absence);
      notify.success(`Відсутність скасовано: ${name(absence.teacher)}`);
      trackFinal(
        `Скасовано відсутність — ${name(absence.teacher)}, ${absencePeriod(absence)}`,
        'Скасовану відсутність не повернути: додайте її знову',
      );
    } catch (error) {
      notify.error(error, 'Не вдалося скасувати відсутність');
    }
  };

  const cancelStandalone = async (change: ChangeDto) => {
    const declined = change.status === 'DECLINED';
    const ok = await confirm({
      title: declined ? 'Прибрати зміну з відмовою?' : 'Скасувати зміну?',
      description: declined
        ? 'Викладач уже відмовився, тож у розкладі групи цієї зміни немає. Її просто приберемо зі списку — нікому нічого не надішлемо.'
        : change.status === 'PENDING_CONFIRMATION' || change.status === 'CONFIRMED'
          ? 'Розклад групи повернеться як був. Якщо викладачу вже надіслали запит, бот повідомить про скасування.'
          : 'Розклад групи повернеться як був.',
      objectName: `${standaloneHeading(change, true)} — ${decisionSummary(change, name)}`,
      confirmLabel: declined ? 'Прибрати' : 'Скасувати зміну',
      cancelLabel: 'Залишити',
      tone: 'danger',
    });
    if (!ok) return;
    try {
      await cancelChange.mutateAsync(change.id);
      notify.success(`Зміну скасовано: ${standaloneHeading(change, true)}`);
      trackFinal(`Скасовано зміну — ${standaloneHeading(change, true)}`, 'Скасовану зміну не повернути: додайте її знову');
    } catch (error) {
      notify.error(error, 'Не вдалося скасувати зміну');
    }
  };

  return { journalAbsence, cancelAbsenceFlow, cancelStandalone };
}
