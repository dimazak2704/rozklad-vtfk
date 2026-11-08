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
  PageHeader,
  Panel,
  ParityMark,
  Section,
  Skeleton,
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
  notify,
} from '@/components/ui';
import { cn } from '@/lib/cn';
import { confirm } from '@/stores/confirm';
import { formatNumericDate, todayInKyiv } from '@/lib/date';
import { countLabel, PARITY_LABEL } from '@/lib/labels';
import { useRegisterCommands } from '@/stores/commands';

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
    { id: 'new-period', label: 'Додати період календаря', group: 'Календар', icon: Plus, perform: () => setPeriod('new') },
  ]);

  const makeActive = async (semester: CalendarSemester) => {
    const ok = await confirm({
      title: `Зробити активним «${semester.name}»?`,
      description: 'Публічний сайт, генератор і облік годин перейдуть на цей семестр. Попередній перестане бути активним.',
      confirmLabel: 'Зробити активним',
      onConfirm: () => activate.mutateAsync(semester.id),
    });
    if (ok) notify.success(`Активний семестр: ${semester.name}`);
  };

  return (
    <div className="flex flex-col gap-5">
      <PageHeader
        title="Календар"
        description="Семестри, чисельник і знаменник по тижнях, канікули, практика, режими навчання й дзвінки."
        actions={
          <Button variant="secondary" iconStart={<Plus aria-hidden="true" className="size-4" />} onClick={() => setSemesterDialog({ semester: null })}>
            Новий семестр
          </Button>
        }
      />

      {semesters.isPending ? (
        <LoadingRegion label="Завантажуємо семестри">
          <div className="flex gap-3">
            <Skeleton className="h-20 w-64" />
            <Skeleton className="h-20 w-64" />
          </div>
        </LoadingRegion>
      ) : semesters.isError ? (
        <ErrorState error={semesters.error} onRetry={() => void semesters.refetch()} />
      ) : !selected ? (
        <EmptyState
          title="Семестрів ще немає"
          description="Створіть семестр: від дати початку й парності першого тижня система порахує чисельник і знаменник."
          action={
            <Button variant="primary" onClick={() => setSemesterDialog({ semester: null })}>
              Створити семестр
            </Button>
          }
        />
      ) : (
        <>
          <div role="group" aria-label="Семестри" className="flex flex-wrap gap-2">
            {semesters.data.map((semester) => {
              const isSelected = semester.id === selected.id;
              return (
                <button
                  key={semester.id}
                  type="button"
                  aria-pressed={isSelected}
                  onClick={() => setParam('semester', semester.active ? null : semester.id)}
                  className={cn(
                    'flex min-w-56 flex-col items-start gap-1 rounded-panel border px-4 py-3 text-start',
                    'transition-[background-color,border-color] duration-150 ease-out',
                    'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus',
                    isSelected ? 'border-accent bg-selected' : 'border-default bg-surface hover:border-strong hover:bg-hover',
                  )}
                >
                  <span className="flex w-full items-center justify-between gap-3">
                    <span className="text-ui font-semibold text-primary">{semester.name}</span>
                    {semester.active ? (
                      <Badge tone="accent" size="sm">
                        Активний
                      </Badge>
                    ) : null}
                  </span>
                  <span className="text-caption text-secondary figures">
                    {formatNumericDate(semester.startsOn)} – {formatNumericDate(semester.endsOn)} ·{' '}
                    {countLabel(semester.teachingWeeks, ['тиждень', 'тижні', 'тижнів'])}
                  </span>
                </button>
              );
            })}
          </div>

          <SemesterSummary
            semester={selected}
            onEdit={() => setSemesterDialog({ semester: selected })}
            onActivate={() => void makeActive(selected)}
            onDelete={() => setDeletingSemester(selected)}
          />

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

function SemesterSummary({
  semester,
  onEdit,
  onActivate,
  onDelete,
}: {
  semester: CalendarSemester;
  onEdit: () => void;
  onActivate: () => void;
  onDelete: () => void;
}) {
  return (
    <Panel flush>
      <dl className="grid grid-cols-2 gap-x-6 gap-y-3 px-4 py-3 sm:grid-cols-4 lg:grid-cols-[repeat(4,minmax(0,1fr))_auto]">
        <div className="flex flex-col gap-0.5">
          <dt className="text-caption text-muted">Дати</dt>
          <dd className="text-ui font-semibold text-primary figures">
            {formatNumericDate(semester.startsOn)} – {formatNumericDate(semester.endsOn)}
          </dd>
        </div>
        <div className="flex flex-col gap-0.5">
          <dt className="text-caption text-muted">Навчальних тижнів</dt>
          <dd className="text-ui font-semibold text-primary figures">
            {semester.teachingWeeks}
            {semester.weeksCount === null ? <span className="font-normal text-muted"> (за датами)</span> : null}
          </dd>
        </div>
        <div className="flex flex-col gap-0.5">
          <dt className="text-caption text-muted">Перший тиждень</dt>
          <dd className="flex items-center gap-1.5 text-ui font-semibold text-primary">
            <ParityMark parity={semester.firstWeekParity} className="text-accent" />
            {PARITY_LABEL[semester.firstWeekParity]}
          </dd>
        </div>
        <div className="flex flex-col gap-0.5">
          <dt className="text-caption text-muted">Дзвінки</dt>
          <dd className="text-ui font-semibold text-primary">{semester.bellProfile?.label ?? 'Типовий профіль'}</dd>
        </div>
        <div className="col-span-2 flex flex-wrap items-center gap-2 sm:col-span-4 lg:col-span-1 lg:justify-end">
          <Button size="sm" variant="secondary" iconStart={<Pencil aria-hidden="true" className="size-4" />} onClick={onEdit}>
            Редагувати
          </Button>
          {!semester.active ? (
            <>
              <Button size="sm" variant="primary" iconStart={<CalendarCheck aria-hidden="true" className="size-4" />} onClick={onActivate}>
                Зробити активним
              </Button>
              <Button size="sm" variant="danger-ghost" iconStart={<Trash aria-hidden="true" className="size-4" />} onClick={onDelete}>
                Видалити
              </Button>
            </>
          ) : null}
        </div>
      </dl>
    </Panel>
  );
}

function WeeksSection({ semester, onOpenPeriod }: { semester: CalendarSemester; onOpenPeriod: (period: CalendarPeriod) => void }) {
  const weeks = useSemesterWeeks(semester.id);
  const periods = useCalendarPeriods(semester.id);
  const override = useOverrideWeek(semester.id);
  const today = todayInKyiv();
  const overridden = weeks.data?.filter((w) => w.overridden).length ?? 0;

  const toggle = (week: CalendarWeek) => {
    const next = week.parity === 'NUMERATOR' ? 'DENOMINATOR' : 'NUMERATOR';
    const parity = next === week.computedParity ? null : next;
    override.mutate(
      { weekStart: week.weekStart, parity },
      {
        onSuccess: () =>
          notify.undo(
            parity
              ? `Тиждень ${week.weekNumber} тепер ${PARITY_LABEL[next]} (вручну)`
              : `Тиждень ${week.weekNumber} знову за чергуванням: ${PARITY_LABEL[next]}`,
            {
              onUndo: () =>
                override.mutate(
                  { weekStart: week.weekStart, parity: week.overridden ? week.parity : null },
                  { onError: (error) => notify.error(error, 'Не вдалося повернути парність') },
                ),
            },
          ),
        onError: (error) => notify.error(error, `Не вдалося змінити парність тижня ${week.weekNumber}`),
      },
    );
  };

  return (
    <Section
      title="Тижні семестру"
      headingLevel={3}
      description="Чисельник і знаменник чергуються від першого тижня. Натисніть тиждень, щоб поміняти парність вручну, наприклад після перенесених днів."
      actions={
        overridden > 0 ? (
          <Badge tone="accent" size="sm">
            Змінено вручну: {overridden}
          </Badge>
        ) : null
      }
    >
      {weeks.isPending ? (
        <LoadingRegion label="Завантажуємо тижні">
          <Skeleton className="h-28 w-full" />
        </LoadingRegion>
      ) : weeks.isError ? (
        <ErrorState error={weeks.error} onRetry={() => void weeks.refetch()} size="sm" />
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
                    isSelected ? 'border-accent bg-selected' : 'border-default bg-surface hover:bg-hover',
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
