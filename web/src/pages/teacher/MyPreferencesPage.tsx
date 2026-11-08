import { Save, Undo2 } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { useBlocker } from 'react-router';
import { usePublicMeta } from '@/api/meta';
import { useMe, useMyPreferences, useMySchedule, useSavePreferences } from '@/api/me';
import { useBellProfiles } from '@/api/public';
import type { BellSlotDto, PreferenceKind, Weekday } from '@/api/types';
import { studyWeek } from '@/components/public/schedule/schedule-params';
import { PreferenceGrid } from '@/components/teacher/PreferenceGrid';
import { cellKey, changedCells, countKinds, KIND_CELL, KIND_TEXT, toList, toMap, type CellKey } from '@/components/teacher/preferences';
import { Button, EmptyState, ErrorState, LoadingRegion, PageHeader, Skeleton, notify } from '@/components/ui';
import { cn } from '@/lib/cn';
import { addDays, nearestStudyDay, todayInKyiv } from '@/lib/date';
import { countLabel, PARITY_LABEL } from '@/lib/labels';
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
  // два тижні — чисельник і знаменник: щоб показати, де в мене пари зараз
  const week = studyWeek(nearestStudyDay(todayInKyiv()));
  const schedule = useMySchedule(week.from, addDays(week.to, 7), { enabled: teacher !== null });
  const [draft, setDraft] = useState<Map<CellKey, PreferenceKind> | null>(null);

  const saved = useMemo(() => toMap(preferences.data ?? []), [preferences.data]);
  const current = draft ?? saved;
  const changed = changedCells(saved, current);
  const counts = countKinds(current);

  const slotsByWeekday = useMemo(() => {
    const active = profiles.data?.profiles?.find((p) => p.id === profiles.data.profile.id);
    const map = new Map<Weekday, BellSlotDto[]>();
    for (const slot of active?.slots ?? []) map.set(slot.weekday, [...(map.get(slot.weekday) ?? []), slot]);
    for (const list of map.values()) list.sort((a, b) => a.pair - b.pair);
    return map;
  }, [profiles.data]);

  const lessons = useMemo(() => {
    const byCell = new Map<CellKey, Map<string, Set<string>>>();
    for (const day of schedule.data?.days ?? []) {
      for (const lesson of day.lessons) {
        if (lesson.teacher?.id !== teacher?.id || lesson.kind === 'CANCELLED') continue;
        const key = cellKey(day.weekday, lesson.pair);
        const labels = byCell.get(key) ?? new Map<string, Set<string>>();
        const label = `${lesson.group.code} · ${lesson.discipline.shortName}`;
        labels.set(label, (labels.get(label) ?? new Set<string>()).add(day.parity));
        byCell.set(key, labels);
      }
    }
    const out = new Map<CellKey, string[]>();
    for (const [key, labels] of byCell) {
      out.set(
        key,
        [...labels].map(([label, parities]) =>
          parities.size === 1 ? `${label} (${PARITY_LABEL[[...parities][0] as 'NUMERATOR' | 'DENOMINATOR']})` : label,
        ),
      );
    }
    return out;
  }, [schedule.data, teacher?.id]);

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

  const onSave = () =>
    save.mutate(toList(current, preferences.data ?? []), {
      onSuccess: () => {
        setDraft(null);
        notify.success('Побажання збережено', { description: 'Генератор врахує їх під час наступного складання розкладу.' });
      },
      onError: (error) => notify.error(error, 'Не вдалося зберегти побажання'),
    });

  // вимкнений запит (ще немає викладача чи семестру) у TanStack Query 5 лишається «pending», тож для побажань — isLoading
  const loading = me.isPending || meta.isPending || preferences.isLoading || profiles.isPending;
  const failure = me.error ?? meta.error ?? preferences.error ?? profiles.error;

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Побажання до розкладу"
        description={`Позначте пари, які вам незручні або неможливі${semester ? ` (${semester.name})` : ''}. Генератор розкладу уникає небажаних пар і не ставить пари туди, де ви не можете.`}
      />

      {failure ? (
        <ErrorState
          error={failure}
          onRetry={() => {
            void me.refetch();
            void meta.refetch();
            void preferences.refetch();
            void profiles.refetch();
          }}
          className="rounded-panel border border-default bg-surface"
        />
      ) : !loading && !teacher ? (
        <EmptyState
          align="start"
          title="Обліковий запис не повʼязаний з викладачем"
          description="Побажання стосуються конкретного викладача. Попросіть диспетчера привʼязати ваш обліковий запис."
        />
      ) : loading ? (
        <LoadingRegion label="Завантажуємо побажання…">
          <Skeleton className="h-[30rem] w-full rounded-panel" />
        </LoadingRegion>
      ) : (
        <>
          <div id="preferences-legend" className="flex flex-wrap items-center gap-x-5 gap-y-2 text-caption text-secondary">
            {(['UNWANTED', 'UNAVAILABLE'] as const).map((kind) => (
              <span key={kind} className="inline-flex items-center gap-2">
                <span aria-hidden="true" className={cn('inline-block h-5 w-8 rounded-plate border', KIND_CELL[kind])} />
                <span>
                  <span className="font-semibold text-primary">{KIND_TEXT[kind]}</span>
                  {kind === 'UNWANTED' ? ' — уникати, якщо можна' : ' — пару сюди не ставити'}
                </span>
              </span>
            ))}
            <span className="inline-flex items-center gap-2">
              <span aria-hidden="true" className="text-meta font-semibold text-muted">4КІ1</span>
              ваша пара за поточним розкладом
            </span>
            <span className="text-muted">Клік — змінити; клавіші: 1, 2, 0 і стрілки.</span>
          </div>

          <PreferenceGrid value={current} onCellChange={onCellChange} slotsByWeekday={slotsByWeekday} lessons={lessons} describedBy="preferences-legend" />

          <p className="text-ui text-secondary" aria-live="polite">
            Небажано: <span className="font-semibold text-primary figures">{counts.UNWANTED}</span> · Неможливо:{' '}
            <span className="font-semibold text-primary figures">{counts.UNAVAILABLE}</span>
            {counts.UNAVAILABLE > 0 ? ' — «неможливо» ставте лише там, де справді не можете: це жорстке обмеження для розкладу.' : null}
          </p>

          <div
            className={cn(
              'sticky bottom-[calc(var(--tabbar-height)+0.75rem)] z-20 flex flex-wrap items-center justify-between gap-3 rounded-panel border border-default bg-raised px-4 py-3 shadow-popover md:bottom-4',
              changed === 0 && 'hidden',
            )}
            role="region"
            aria-label="Незбережені зміни"
          >
            <p className="text-ui text-primary">
              Не збережено: <span className="font-semibold figures">{countLabel(changed, CELL_FORMS)}</span>
            </p>
            <div className="flex gap-2">
              <Button variant="ghost" iconStart={<Undo2 aria-hidden="true" className="size-4" />} onClick={() => setDraft(null)} disabled={save.isPending}>
                Скасувати
              </Button>
              <Button variant="primary" iconStart={<Save aria-hidden="true" className="size-4" />} loading={save.isPending} onClick={onSave}>
                Зберегти
              </Button>
            </div>
          </div>
        </>
      )}
    </div>
  );
}
