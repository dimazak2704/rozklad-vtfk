import { CalendarCheck, Pencil, Plus, Trash } from 'lucide-react';
import { useState } from 'react';
import { useSearchParams } from 'react-router';
import {
  activeSemesterOf,
  useActivateSemester,
  useBellProfiles,
  useCalendarPeriods,
  useDeleteSemester,
  useOverrideWeek,
  useSemesters,
  useSemesterWeeks,
  useShiftWindows,
  type CalendarBellProfile,
  type CalendarPeriod,
  type CalendarSemester,
  type CalendarWeek,
} from '@/api/calendar';
import { BellProfileEditor } from '@/components/calendar/BellProfileEditor';
import { PeriodsTable } from '@/components/calendar/PeriodsTable';
import { SemesterFormDialog } from '@/components/calendar/SemesterForm';
import { SemesterTimeline } from '@/components/calendar/SemesterTimeline';
import { DeleteDialog } from '@/components/catalog/DeleteDialog';
import {
  Badge,
  Button,
  EmptyState,
  ErrorState,
  LoadingRegion,
  PageErrorBanner,
  PageHeader,
  Panel,
  Section,
  Select,
  type MoreMenuItem,
  Skeleton,
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
  notify,
} from '@/components/ui';
import { cn } from '@/lib/cn';
import { confirm } from '@/stores/confirm';
import { formatNumericDate, startOfWeek, todayInKyiv } from '@/lib/date';
import { countLabel, PARITY_LABEL } from '@/lib/labels';
import { useRegisterCommands } from '@/stores/commands';
import { qk } from '@/api/queryKeys';
import { track, trackFinal } from '@/lib/undo/actions';
import { useHotkeys } from '@/hooks/useHotkeys';

type Tab = 'weeks' | 'bells';
type SemesterDialog = { semester: CalendarSemester | null } | null;

/** Календар: семестри й активний семестр, чисельник/знаменник по тижнях, періоди, дзвінки. */
export default function CalendarPage() {
  const [params, setParams] = useSearchParams();
  const semesters = useSemesters();
  const tab: Tab = params.get('tab') === 'bells' ? 'bells' : 'weeks';
  const selected = semesters.data?.find((s) => s.id === params.get('semester')) ?? activeSemesterOf(semesters.data);
  const [semesterDialog, setSemesterDialog] = useState<SemesterDialog>(null);
  const [deletingSemester, setDeletingSemester] = useState<CalendarSemester | null>(null);
  const [period, setPeriod] = useState<CalendarPeriod | 'new' | null>(null);
  const activate = useActivateSemester();
  const removeSemester = useDeleteSemester();

  const setParam = (key: string, value: string | null) =>
    setParams(
      (prev) => {
        const next = new URLSearchParams(prev);
        if (value) next.set(key, value);
        else next.delete(key);
        return next;
      },
      { replace: true },
    );

  useRegisterCommands([
    { id: 'new-semester', label: 'Створити семестр', group: 'Календар', icon: Plus, perform: () => setSemesterDialog({ semester: null }) },
    { id: 'new-period', label: 'Додати період календаря', group: 'Календар', icon: Plus, shortcut: 'n', perform: () => setPeriod('new') },
  ]);
  useHotkeys([
    { keys: 'n', handler: () => setPeriod('new'), description: 'Новий період календаря', group: 'Календар', enabled: Boolean(selected) && tab === 'weeks' },
  ]);

  const makeActive = async (semester: CalendarSemester) => {
    const ok = await confirm({
      title: `Зробити активним «${semester.name}»?`,
      description: 'Публічний сайт, генератор і облік годин перейдуть на цей семестр. Попередній перестане бути активним.',
      confirmLabel: 'Зробити активним',
      onConfirm: () => activate.mutateAsync(semester.id),
    });
    if (!ok) return;
    notify.success(`Активний семестр: ${semester.name}`);
    // повернути можна, лише якщо до того був інший активний семестр
    const previous = semesters.data?.find((s) => s.active && s.id !== semester.id);
    if (previous) {
      track({
        label: `Активним зроблено семестр «${semester.name}»`,
        invalidate: [qk.admin.calendar.all],
        silent: true,
        undo: () => activate.mutateAsync(previous.id),
        redo: () => activate.mutateAsync(semester.id),
      });
    } else {
      trackFinal(`Активним зроблено семестр «${semester.name}»`);
    }
  };

  // семестр обирають у заголовку; усе, що з ним роблять рідко, — у «⋯»
  const more: MoreMenuItem[] = [
    ...(selected ? [{ label: 'Змінити семестр', icon: <Pencil aria-hidden="true" className="size-4" />, onSelect: () => setSemesterDialog({ semester: selected }) }] : []),
    { label: 'Новий семестр', icon: <Plus aria-hidden="true" className="size-4" />, onSelect: () => setSemesterDialog({ semester: null }) },
    ...(selected && !selected.active
      ? [{ label: 'Видалити семестр', icon: <Trash aria-hidden="true" className="size-4" />, tone: 'danger' as const, separatorBefore: true, onSelect: () => setDeletingSemester(selected) }]
      : []),
  ];

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Календар"
        meta={selected ? semesterSentence(selected) : undefined}
        controls={
          semesters.data && semesters.data.length > 1 && selected ? (
            <Select<string>
              aria-label="Семестр"
              className="w-full sm:w-64"
              value={selected.id}
              onValueChange={(id) => setParam('semester', semesters.data.find((s) => s.id === id)?.active ? null : id)}
              options={semesters.data.map((s) => ({ value: s.id, label: s.name, description: s.active ? 'Активний' : undefined }))}
            />
          ) : undefined
        }
        primaryAction={
          selected && !selected.active ? (
            <Button variant="primary" iconStart={<CalendarCheck aria-hidden="true" className="size-4" />} onClick={() => void makeActive(selected)}>
              Зробити активним
            </Button>
          ) : undefined
        }
        more={more}
      />

      {semesters.isPending ? (
        <LoadingRegion label="Завантажуємо семестри">
          <div className="flex gap-3">
            <Skeleton className="h-20 w-64" />
            <Skeleton className="h-20 w-64" />
          </div>
        </LoadingRegion>
      ) : semesters.isError ? (
        <PageErrorBanner error={semesters.error} title="Семестри не завантажено" retrying={semesters.isFetching} onRetry={() => void semesters.refetch()} />
      ) : !selected ? (
        <EmptyState
          title="Семестрів ще немає."
          description="Від дати початку й парності першого тижня система сама порахує чисельник і знаменник."
          action={
            <Button variant="primary" onClick={() => setSemesterDialog({ semester: null })}>
              Створити семестр
            </Button>
          }
        />
      ) : (
        <>
          <Tabs value={tab} onValueChange={(value) => setParam('tab', value === 'weeks' ? null : value)}>
            <TabsList aria-label="Розділи календаря">
              <TabsTrigger value="weeks">Тижні й періоди</TabsTrigger>
              <TabsTrigger value="bells">Дзвінки</TabsTrigger>
            </TabsList>
            <TabsContent value="weeks" className="flex flex-col gap-8">
              <WeeksSection semester={selected} onOpenPeriod={setPeriod} />
              <PeriodsTable semester={selected} opened={period} onOpen={setPeriod} />
            </TabsContent>
            <TabsContent value="bells">
              <BellsSection />
            </TabsContent>
          </Tabs>
        </>
      )}

      {semesterDialog ? (
        <SemesterFormDialog
          key={semesterDialog.semester?.id ?? 'new'}
          semester={semesterDialog.semester}
          open
          onOpenChange={(open) => {
            if (!open) setSemesterDialog(null);
          }}
          onSaved={(saved) => setParam('semester', saved.active ? null : saved.id)}
        />
      ) : null}
      <DeleteDialog
        target={deletingSemester ? { id: deletingSemester.id, name: deletingSemester.name } : null}
        onClose={() => setDeletingSemester(null)}
        title="Видалити семестр?"
        description="Видалити можна лише порожній семестр: без навантаження, розкладу, періодів і розпоряджень."
        onDelete={async (id) => {
          await removeSemester.mutateAsync(id);
          setParam('semester', null);
        }}
        successMessage={(name) => `Семестр «${name}» видалено`}
      />
    </div>
  );
}

/**
 * Під заголовком — лише межі семестру: «01.09.2026 – 30.12.2026». Тижні й парність видно на смузі нижче,
 * дзвінки — на вкладці «Дзвінки», тож тут вони не повторюються.
 */
function semesterSentence(semester: CalendarSemester): string {
  return `${formatNumericDate(semester.startsOn)} – ${formatNumericDate(semester.endsOn)}${semester.active ? '' : ' · не активний'}`;
}

function WeeksSection({ semester, onOpenPeriod }: { semester: CalendarSemester; onOpenPeriod: (period: CalendarPeriod) => void }) {
  const weeks = useSemesterWeeks(semester.id);
  const periods = useCalendarPeriods(semester.id);
  const override = useOverrideWeek(semester.id);
  const today = todayInKyiv();
  const overridden = weeks.data?.filter((w) => w.overridden).length ?? 0;
  const current = weeks.data?.find((w) => w.weekStart === startOfWeek(today));

  const toggle = (week: CalendarWeek) => {
    const next = week.parity === 'NUMERATOR' ? 'DENOMINATOR' : 'NUMERATOR';
    const parity = next === week.computedParity ? null : next;
    const previous = week.overridden ? week.parity : null;
    override.mutate(
      { weekStart: week.weekStart, parity },
      {
        // парність тижня — у журнал дій: Ctrl+Z чи «Скасувати» повертають попередню
        onSuccess: () =>
          track({
            label: parity
              ? `Тиждень ${week.weekNumber} позначено як ${PARITY_LABEL[next]} (вручну)`
              : `Тиждень ${week.weekNumber} повернуто до чергування: ${PARITY_LABEL[next]}`,
            invalidate: [qk.admin.calendar.all],
            undo: () => override.mutateAsync({ weekStart: week.weekStart, parity: previous }),
            redo: () => override.mutateAsync({ weekStart: week.weekStart, parity }),
          }),
        onError: (error) => notify.error(error, `Не вдалося змінити парність тижня ${week.weekNumber}`),
      },
    );
  };

  return (
    <Section
      title="Тижні семестру"
      headingLevel={3}
      description={
        <>
          {current ? `Зараз ${current.weekNumber}-й тиждень, ${PARITY_LABEL[current.parity]}. ` : null}
          Натисніть тиждень, щоб поміняти парність вручну
          {overridden > 0 ? ` (уже змінено: ${overridden})` : ''}.
        </>
      }
    >
      {weeks.isPending ? (
        <LoadingRegion label="Завантажуємо тижні">
          <Skeleton className="h-28 w-full" />
        </LoadingRegion>
      ) : weeks.isError ? (
        <ErrorState error={weeks.error} onRetry={() => void weeks.refetch()} size="sm" title="Тижні не завантажено" />
      ) : (
        <SemesterTimeline
          weeks={weeks.data}
          periods={periods.data ?? []}
          today={today}
          onToggleWeek={override.isPending ? null : toggle}
          onOpenPeriod={onOpenPeriod}
        />
      )}
    </Section>
  );
}

function BellsSection() {
  const profiles = useBellProfiles();
  const shiftWindows = useShiftWindows();
  // null — профіль за замовчуванням; creating — редагуємо новий профіль (копію типового)
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const list = profiles.data ?? [];
  const fallback = list.find((p) => p.defaultProfile) ?? list[0] ?? null;
  const selected: CalendarBellProfile | null = creating ? null : (list.find((p) => p.id === selectedId) ?? fallback);

  if (profiles.isPending) {
    return (
      <LoadingRegion label="Завантажуємо дзвінки">
        <Skeleton className="h-72 w-full" />
      </LoadingRegion>
    );
  }
  if (profiles.isError) return <ErrorState error={profiles.error} onRetry={() => void profiles.refetch()} />;

  const windows = shiftWindows.data ?? [];
  const rangeOf = (shift: 1 | 2, weekday: number) => {
    const w = windows.find((x) => x.shift === shift && x.weekday === weekday);
    return w ? `${w.firstPair}–${w.lastPair}` : '—';
  };

  return (
    <div className="grid gap-5 xl:grid-cols-[15rem_minmax(0,1fr)]">
      <div className="flex flex-col gap-3">
        <ul role="list" aria-label="Профілі дзвінків" className="flex flex-col gap-1.5">
          {list.map((profile) => {
            const isSelected = !creating && selected?.id === profile.id;
            return (
              <li key={profile.id}>
                <button
                  type="button"
                  aria-pressed={isSelected}
                  onClick={() => {
                    setCreating(false);
                    setSelectedId(profile.id);
                  }}
                  className={cn(
                    'flex w-full flex-col items-start gap-1 rounded-control border px-3 py-2 text-start',
                    'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus',
                    isSelected ? 'border-accent bg-selected' : 'border-default bg-surface hover-fine:bg-hover',
                  )}
                >
                  <span className="text-ui font-semibold text-primary">{profile.name}</span>
                  <span className="flex flex-wrap gap-1">
                    {profile.defaultProfile ? (
                      <Badge tone="accent" size="sm">
                        Типовий
                      </Badge>
                    ) : null}
                    {!profile.active ? (
                      <Badge tone="neutral" size="sm">
                        Вимкнено
                      </Badge>
                    ) : null}
                    <span className="text-meta text-muted figures">
                      {countLabel(new Set(profile.slots.map((s) => s.pair)).size, ['пара', 'пари', 'пар'])} на день
                    </span>
                  </span>
                </button>
              </li>
            );
          })}
        </ul>
        <Button variant="secondary" size="sm" iconStart={<Plus aria-hidden="true" className="size-4" />} onClick={() => setCreating(true)}>
          Новий профіль
        </Button>
        {windows.length > 0 ? (
          <div className="flex flex-col gap-1.5 rounded-panel border border-default bg-sunken px-3 py-2.5">
            <p className="text-caption font-semibold text-secondary">Пари змін</p>
            <p className="text-caption text-secondary figures">1 зміна: пари {rangeOf(1, 1)} щодня</p>
            <p className="text-caption text-secondary figures">
              2 зміна: {rangeOf(2, 1)} у пн–чт, {rangeOf(2, 5)} у пʼятницю
            </p>
          </div>
        ) : null}
      </div>
      <Panel title={selected ? selected.name : 'Новий профіль дзвінків'} description={selected ? undefined : 'Копія типового профілю — змініть час і збережіть.'}>
        <BellProfileEditor
          key={selected ? `${selected.id}-${selected.version}` : 'new'}
          profile={selected}
          template={fallback}
          onSaved={(saved) => {
            setCreating(false);
            setSelectedId(saved.id);
          }}
          onDeleted={() => setSelectedId(null)}
        />
      </Panel>
    </div>
  );
}
