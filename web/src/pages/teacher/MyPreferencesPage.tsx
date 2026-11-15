import { Save, Undo2 } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { useBlocker } from 'react-router';
import { usePublicMeta } from '@/api/meta';
import { useMe, useMyPreferences, useMyTemplate, useSavePreferences } from '@/api/me';
import { useBellProfiles } from '@/api/public';
import type { BellSlotDto, PreferenceKind, Weekday } from '@/api/types';
import { PreferenceGrid, PreferenceLegend } from '@/components/teacher/PreferenceGrid';
import { changedCells, templateCells, toList, toMap, type CellKey } from '@/components/teacher/preferences';
import { Button, EmptyState, LoadingRegion, PageErrorScope, PageHeader, Skeleton, notify, toastClearanceRef } from '@/components/ui';
import { cn } from '@/lib/cn';
import { countLabel } from '@/lib/labels';
import { confirm } from '@/stores/confirm';

const CELL_FORMS = ['клітинка', 'клітинки', 'клітинок'] as const;

/** Побажання до розкладу: сітка «день × пара» з небажаними й неможливими парами для генератора. */
export default function MyPreferencesPage() {
  const me = useMe();
  const meta = usePublicMeta();
  const semester = meta.data?.semester;
  const teacher = me.data?.teacher ?? null;
  const preferences = useMyPreferences(teacher ? semester?.id : undefined);
  const save = useSavePreferences(semester?.id);
  const profiles = useBellProfiles();
  // мої пари в базовому розкладі семестру — не разові заміни цього тижня і не порожні тижні груп на НС чи практиці
  const template = useMyTemplate(teacher ? semester?.id : undefined);
  const [draft, setDraft] = useState<Map<CellKey, PreferenceKind> | null>(null);

  const saved = useMemo(() => toMap(preferences.data ?? []), [preferences.data]);
  const current = draft ?? saved;
  const changed = changedCells(saved, current);

  const slotsByWeekday = useMemo(() => {
    const active = profiles.data?.profiles?.find((p) => p.id === profiles.data.profile.id);
    const map = new Map<Weekday, BellSlotDto[]>();
    for (const slot of active?.slots ?? []) map.set(slot.weekday, [...(map.get(slot.weekday) ?? []), slot]);
    for (const list of map.values()) list.sort((a, b) => a.pair - b.pair);
    return map;
  }, [profiles.data]);

  const lessons = useMemo(() => templateCells(template.data ?? []), [template.data]);

  const blocker = useBlocker(({ currentLocation, nextLocation }) => changed > 0 && currentLocation.pathname !== nextLocation.pathname);
  useEffect(() => {
    if (blocker.state !== 'blocked') return;
    void confirm({
      title: 'Піти без збереження?',
      description: `У сітці ${countLabel(changed, CELL_FORMS)} не збережено.`,
      confirmLabel: 'Піти без збереження',
      cancelLabel: 'Лишитися',
      tone: 'danger',
    }).then((leave) => (leave ? blocker.proceed() : blocker.reset()));
  }, [blocker, changed]);

  const onCellChange = (key: CellKey, kind: PreferenceKind | null) =>
    setDraft((previous) => {
      const next = new Map(previous ?? saved);
      if (kind) next.set(key, kind);
      else next.delete(key);
      return next;
    });

  // скасувати чернетку — з можливістю повернути її, а не через «Ви впевнені?»
  const onDiscard = () => {
    const previous = draft;
    setDraft(null);
    notify.undo('Зміни скасовано', { onUndo: () => setDraft(previous) });
  };

  const onSave = () =>
    save.mutate(toList(current, preferences.data ?? []), {
      onSuccess: () => {
        setDraft(null);
        notify.success('Побажання збережено', { description: 'Генератор врахує їх, коли складатиме наступний розклад.' });
      },
      onError: (error) => notify.error(error, 'Не вдалося зберегти побажання'),
    });

  // вимкнений запит (ще немає викладача чи семестру) у TanStack Query 5 лишається «pending», тож для побажань — isLoading
  const loading = me.isPending || meta.isPending || preferences.isLoading || profiles.isPending;
  const failed = me.isError || meta.isError || preferences.isError || profiles.isError;

  return (
    <div className="flex flex-col gap-8">
      <PageHeader
        title="Побажання до розкладу"
        size="headline"
        meta={semester ? <span>{semester.name}</span> : undefined}
      />

      <PageErrorScope queries={[me, meta, preferences, profiles]}>
        {failed ? null : !loading && !teacher ? (
          <EmptyState
            title="Обліковий запис ще не повʼязаний з викладачем."
            description="Побажання стосуються конкретного викладача. Попросіть диспетчера привʼязати ваш обліковий запис."
          />
        ) : loading ? (
          <LoadingRegion label="Завантажуємо побажання…">
            <Skeleton className="h-[30rem] w-full rounded-panel" />
          </LoadingRegion>
        ) : (
          <div className="flex flex-col gap-5">
            {/* одне речення, як користуватися, і ключ до значків: на телефоні в клітинці лише значок */}
            <div className="flex flex-col gap-2">
              <p id="preferences-hint" className="max-w-prose text-body text-secondary">
                Натисніть пару, яка вам незручна, а ще раз — якщо прийти тоді не можете зовсім.
              </p>
              <PreferenceLegend />
            </div>

            <PreferenceGrid value={current} onCellChange={onCellChange} slotsByWeekday={slotsByWeekday} lessons={lessons} describedBy="preferences-hint" />

            <div
              ref={toastClearanceRef}
              className={cn(
                'sticky bottom-[calc(var(--tabbar-height)+0.75rem)] z-20 flex flex-wrap items-center justify-between gap-3 rounded-popover border border-default bg-raised px-4 py-3 shadow-popover md:bottom-4',
                changed === 0 && 'hidden',
              )}
              role="region"
              aria-label="Незбережені зміни"
            >
              <p className="text-ui text-primary">
                Не збережено: <span className="font-[650] figures">{countLabel(changed, CELL_FORMS)}</span>
              </p>
              <div className="flex gap-2">
                <Button variant="ghost" iconStart={<Undo2 aria-hidden="true" className="size-4" />} onClick={onDiscard} disabled={save.isPending}>
                  Скасувати
                </Button>
                <Button variant="primary" iconStart={<Save aria-hidden="true" className="size-4" />} loading={save.isPending} onClick={onSave}>
                  Зберегти
                </Button>
              </div>
            </div>
          </div>
        )}
      </PageErrorScope>
    </div>
  );
}
