import { useServerEvent } from '@/api/live';
import type { Uuid } from '@/api/types';
import { notify } from '@/components/ui';
import { answerToast, teacherAnswered } from '../model';
import { useTeacherLabel } from '../useTeacherLabel';
import type { ChangesWorkspace } from './useChangesWorkspace';

interface AnswerInput {
  workspace: ChangesWorkspace;
  /** Чи зміна — власна дія диспетчера (про неї тост уже був). */
  isOwn: (changeId: Uuid) => boolean;
  /** Короткий «пульс» на рядку пари. */
  pulse: (lessonId: Uuid) => void;
}

/** Відповідь викладача в Telegram: тост одним реченням і короткий «пульс» на рядку пари. */
export function useTeacherAnswerToasts({ workspace, isOwn, pulse }: AnswerInput) {
  const name = useTeacherLabel();
  useServerEvent('change.updated', (change) => {
    const last = change.history.at(-1);
    if (!last || !teacherAnswered(last.from, last.to, change.approvalMode)) return;
    if (isOwn(change.id) || change.date < workspace.from || change.date > workspace.to) return;
    const entry = workspace.entries.find((e) => e.item.decision?.changeId === change.id);
    const toast = answerToast(change, change.new.teacher ? name(change.new.teacher) : 'Викладач');
    notify[toast.tone](toast.text);
    if (entry) pulse(entry.item.lesson.id);
  });
}
