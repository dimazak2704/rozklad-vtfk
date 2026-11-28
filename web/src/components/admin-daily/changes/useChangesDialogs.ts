import { useState } from 'react';
import type { AbsenceDto } from '@/api/types';
import type { NewChangePrefill } from './new-change';

/**
 * Діалоги екрана замін: відсутність (нова чи правка) і нова зміна (порожня чи із заготовкою). `seq` росте з
 * кожним відкриттям — діалог монтується наново й не тягне стан минулого відкриття.
 */
export function useChangesDialogs({ onChangeDialogClosed }: { onChangeDialogClosed: () => void }) {
  const [absenceDialog, setAbsenceDialog] = useState<{ open: boolean; absence: AbsenceDto | null; seq: number }>({
    open: false,
    absence: null,
    seq: 0,
  });
  const [changeDialog, setChangeDialog] = useState<{ open: boolean; prefill: NewChangePrefill | null; seq: number }>({
    open: false,
    prefill: null,
    seq: 0,
  });

  const openAbsence = (absence: AbsenceDto | null) => setAbsenceDialog((prev) => ({ open: true, absence, seq: prev.seq + 1 }));
  const openChangeWith = (prefill: NewChangePrefill | null) => setChangeDialog((prev) => ({ open: true, prefill, seq: prev.seq + 1 }));

  return {
    absenceDialog,
    changeDialog,
    openAbsence,
    // без аргументів: кнопки й гарячі клавіші передають сюди подію, а не заготовку форми
    openNewAbsence: () => openAbsence(null),
    openChangeWith,
    openNewChange: () => openChangeWith(null),
    setAbsenceDialogOpen: (open: boolean) => setAbsenceDialog((prev) => ({ ...prev, open })),
    setChangeDialogOpen: (open: boolean) => {
      if (open) {
        setChangeDialog((prev) => ({ ...prev, open }));
        return;
      }
      setChangeDialog((prev) => ({ ...prev, open: false }));
      onChangeDialogClosed();
    },
  };
}
