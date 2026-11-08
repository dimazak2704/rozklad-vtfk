import { Cpu, Lock, PanelRightOpen, Plus } from 'lucide-react';
import { useMemo, useRef, useState } from 'react';
import { Link } from 'react-router';
import {
  usePlanningCatalog,
  useShiftWindows,
  useVersionConflicts,
  useVersionLessons,
  useVersionMetrics,
  type TimetableConflict,
} from '@/api/timetable';
import type { MetricsDto, Parity, TemplateLessonDto, TimetableVersionDto, Uuid, Weekday } from '@/api/types';
import {
  Button,
  buttonClass,
  Drawer,
  DrawerContent,
  EmptyState,
  ErrorState,
  LoadingRegion,
  Skeleton,
} from '@/components/ui';
import { useHotkeys } from '@/hooks/useHotkeys';
import { cn } from '@/lib/cn';
import { useRegisterCommands } from '@/stores/commands';
import { confirm } from '@/stores/confirm';
import { TimetableBoard, type BoardFocus } from './board/TimetableBoard';
import { LessonDialog } from './dialogs/LessonDialog';
import { MoveDialog } from './dialogs/MoveDialog';
import { EditorToolbar } from './EditorToolbar';
import { buildEntities, EMPTY_FILTERS, entityIdOf, filterEntities, type BoardEntity, type BoardFilters, type BoardView } from './model/grid';
import { toLessonRequest } from './model/history';
import { EMPTY_LESSON_FORM, formValuesOf, type LessonFormValues } from './model/lessonForm';
import { lessonTitle } from './model/text';
import { ConflictsPanel, type ConflictSeverityTab } from './panels/ConflictsPanel';
import { LessonInspector } from './panels/LessonInspector';
import { MetricsBar } from './panels/MetricsBar';
import { useLessonEditing } from './useLessonEditing';
import { useMediaQuery } from './useMediaQuery';

interface TimetableEditorProps {
  version: TimetableVersionDto;
  /** Метрики опублікованої версії для порівняння (null — дивимось саму опубліковану або її немає). */
  baseline: MetricsDto | null;
  view: BoardView;
  onViewChange: (view: BoardView) => void;
  filters: BoardFilters;
  onFiltersChange: (filters: BoardFilters) => void;
  /** Створити чернетку-копію з поточної (для опублікованої чи архівної версії). */
  onCreateDraft: () => void;
  /** Запит «показати пару» ззовні (з порівняння версій). */
  initialFocusLessonId: Uuid | null;
}

const NO_CONFLICTS: TimetableConflict[] = [];

/**
 * Редактор версії: тулбар, табло метрик, дошка з сітками й бічна панель (вибрана пара + конфлікти).
 * Правки — лише в чернетці; опублікована й архівна версії відкриваються для перегляду.
 */
export function TimetableEditor({
  version,
  baseline,
  view,
  onViewChange,
  filters,
  onFiltersChange,
  onCreateDraft,
  initialFocusLessonId,
}: TimetableEditorProps) {
  const editable = version.status === 'DRAFT';
  const wide = useMediaQuery('(min-width: 1440px)');
  const catalog = usePlanningCatalog();
  const windowsQuery = useShiftWindows();
  const lessonsQuery = useVersionLessons(version.id);
  const conflictsQuery = useVersionConflicts(version.id);
  const metricsQuery = useVersionMetrics(version.id);
  const editing = useLessonEditing(version.id);
  const searchRef = useRef<HTMLInputElement>(null);

  const [selectedId, setSelectedId] = useState<Uuid | null>(initialFocusLessonId);
  const [focus, setFocus] = useState<BoardFocus | null>(() =>
    initialFocusLessonId ? { entityId: null, lessonId: initialFocusLessonId, weekday: 1, pair: 1, nonce: 1 } : null,
  );
  const [conflictTab, setConflictTab] = useState<ConflictSeverityTab>('HARD');
  const [panelOpen, setPanelOpen] = useState(false);
  const [lessonDialog, setLessonDialog] = useState<{ lesson: TemplateLessonDto | null; initial: LessonFormValues } | null>(null);
  const [moving, setMoving] = useState<TemplateLessonDto | null>(null);

  const lessons = useMemo(() => lessonsQuery.data ?? [], [lessonsQuery.data]);
  const windows = useMemo(() => windowsQuery.data ?? [], [windowsQuery.data]);
  const lessonsById = useMemo(() => new Map(lessons.map((l) => [l.id, l])), [lessons]);
  const selected = selectedId ? (lessonsById.get(selectedId) ?? null) : null;

  const conflictsByLesson = useMemo(() => {
    const map = new Map<Uuid, TimetableConflict[]>();
    for (const conflict of [...(conflictsQuery.data?.hard ?? []), ...(conflictsQuery.data?.soft ?? [])]) {
      for (const id of conflict.lessonIds) map.set(id, [...(map.get(id) ?? []), conflict]);
    }
    return map;
  }, [conflictsQuery.data]);
  const hardLessonIds = useMemo(() => new Set((conflictsQuery.data?.hard ?? []).flatMap((c) => c.lessonIds)), [conflictsQuery.data]);

  const entities = useMemo(
    () => (catalog.data ? buildEntities(view, lessons, catalog.data) : []),
    [catalog.data, lessons, view],
  );
  const visible = useMemo(() => filterEntities(entities, filters, hardLessonIds), [entities, filters, hardLessonIds]);
  const departments = useMemo(
    () => [...new Set((catalog.data?.groups ?? []).map((g) => g.department))].sort((a, b) => a.localeCompare(b, 'uk')),
    [catalog.data],
  );
  const groupShift = useMemo(() => new Map((catalog.data?.groups ?? []).map((g) => [g.id, g.shift])), [catalog.data]);
  const roomsById = useMemo(() => new Map((catalog.data?.rooms ?? []).map((r) => [r.id, r])), [catalog.data]);

  // ── дії

  const openAdd = (entity?: BoardEntity, weekday?: Weekday, pair?: number, parity?: Parity) => {
    if (!editable) return;
    const initial: LessonFormValues = { ...EMPTY_LESSON_FORM };
    if (weekday) initial.weekday = weekday;
    if (pair) initial.pair = pair;
    if (parity) initial.parity = parity;
    if (entity?.view === 'group') initial.groupId = entity.id;
    if (entity?.view === 'teacher') initial.teacherId = entity.id;
    if (entity?.view === 'room') initial.roomId = entity.id;
    setLessonDialog({ lesson: null, initial });
  };

  const openEdit = (lesson: TemplateLessonDto) => {
    if (editable) setLessonDialog({ lesson, initial: formValuesOf(lesson) });
  };

  const removeLesson = async (lesson: TemplateLessonDto) => {
    const confirmed = await confirm({
      title: 'Видалити пару з чернетки?',
      description: 'Скасувати можна одразу в сповіщенні або через Ctrl+Z.',
      objectName: lessonTitle(lesson),
      tone: 'danger',
      confirmLabel: 'Видалити',
    });
    if (confirmed && (await editing.deleteLesson(lesson))) setSelectedId(null);
  };

  const togglePin = (lesson: TemplateLessonDto) =>
    void editing.updateLesson(lesson, { ...toLessonRequest(lesson), pinned: !lesson.pinned }).catch(() => undefined);

  /** Показати конфлікт у сітці: сутність поточного подання, за потреби — з фільтром на неї. */
  const focusConflict = (conflict: TimetableConflict) => {
    const involved = conflict.lessonIds.map((id) => lessonsById.get(id)).filter((l): l is TemplateLessonDto => Boolean(l));
    const atCell = involved.find((l) => l.pair === conflict.pair && (conflict.weekday == null || l.weekday === conflict.weekday));
    const anchor = atCell ?? involved[0];
    if (!anchor) return;
    let targetView = view;
    let entityId = entityIdOf(view, anchor);
    if (!entityId) {
      targetView = 'group';
      entityId = anchor.group.id;
      onViewChange('group');
    }
    const shown = visible.some((entity) => entity.id === entityId);
    if (!shown || targetView !== view) {
      const label = targetView === 'group' ? anchor.group.code : (entities.find((e) => e.id === entityId)?.label ?? '');
      onFiltersChange({ ...EMPTY_FILTERS, query: label });
    }
    if (atCell) setSelectedId(atCell.id);
    setFocus({
      entityId,
      weekday: conflict.weekday ?? anchor.weekday,
      pair: conflict.pair,
      lessonId: atCell?.id ?? null,
      nonce: Date.now(),
    });
    setPanelOpen(false);
  };

  useHotkeys([
    { keys: 'mod+z', handler: () => void editing.undo(), description: 'Скасувати останню правку', group: 'Базовий розклад', enabled: editable },
    { keys: 'mod+shift+z', handler: () => void editing.redo(), description: 'Повторити скасовану правку', group: 'Базовий розклад', enabled: editable },
    { keys: 'mod+y', handler: () => void editing.redo(), enabled: editable },
    { keys: 'n', handler: () => openAdd(), description: 'Додати пару', group: 'Базовий розклад', enabled: editable },
    {
      keys: 'e',
      handler: () => selected && openEdit(selected),
      description: 'Змінити вибрану пару (або F2 на парі)',
      group: 'Базовий розклад',
      enabled: editable && selected !== null,
    },
    {
      keys: 'm',
      handler: () => selected && setMoving(selected),
      description: 'Перемістити вибрану пару через діалог',
      group: 'Базовий розклад',
      enabled: editable && selected !== null,
    },
    {
      keys: 'delete',
      handler: () => selected && void removeLesson(selected),
      description: 'Видалити вибрану пару',
      group: 'Базовий розклад',
      enabled: editable && selected !== null,
    },
    { keys: '/', handler: () => searchRef.current?.focus(), description: 'Пошук групи, викладача чи аудиторії', group: 'Базовий розклад' },
  ]);

  useRegisterCommands([
    ...(editable ? [{ id: 'add-lesson', label: 'Додати пару в чернетку', group: 'Базовий розклад', icon: Plus, shortcut: 'n', perform: () => openAdd() }] : []),
    {
      id: 'show-conflicts',
      label: 'Показати жорсткі конфлікти версії',
      group: 'Базовий розклад',
      perform: () => {
        setConflictTab('HARD');
        if (!wide) setPanelOpen(true);
      },
    },
    { id: 'view-teachers', label: 'Сітка за викладачами', group: 'Базовий розклад', perform: () => onViewChange('teacher') },
    { id: 'view-rooms', label: 'Сітка за аудиторіями', group: 'Базовий розклад', perform: () => onViewChange('room') },
  ]);

  // ── стани завантаження

  const loadError = catalog.error ?? lessonsQuery.error ?? windowsQuery.error;
  const loading = catalog.isPending || lessonsQuery.isPending || windowsQuery.isPending;

  const sidePanel = (
    <>
      <LessonInspector
        lesson={selected}
        conflicts={selected ? (conflictsByLesson.get(selected.id) ?? NO_CONFLICTS) : NO_CONFLICTS}
        editable={editable}
        busy={editing.pending}
        onEdit={openEdit}
        onMove={setMoving}
        onTogglePin={togglePin}
        onDelete={(lesson) => void removeLesson(lesson)}
      />
      <ConflictsPanel
        hard={conflictsQuery.data?.hard}
        soft={conflictsQuery.data?.soft}
        loading={conflictsQuery.isPending}
        error={conflictsQuery.error}
        onRetry={() => void conflictsQuery.refetch()}
        tab={conflictTab}
        onTabChange={setConflictTab}
        onFocusConflict={focusConflict}
        className="min-h-80 flex-1"
      />
    </>
  );

  return (
    <div className="flex flex-col gap-4">
      <MetricsBar
        versionId={version.id}
        metrics={metricsQuery.data}
        baseline={baseline}
        hardConflicts={conflictsQuery.data?.hard.length ?? version.hardConflicts}
        loading={metricsQuery.isPending}
        onShowConflicts={() => {
          setConflictTab('HARD');
          if (!wide) setPanelOpen(true);
        }}
      />

      {!editable ? (
        <div className="flex flex-wrap items-center gap-x-4 gap-y-2 rounded-panel border border-default bg-sunken px-4 py-3">
          <p className="flex min-w-0 flex-1 items-start gap-2 text-ui text-secondary">
            <Lock aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-muted" />
            {version.status === 'PUBLISHED'
              ? 'Ця версія опублікована й діє — напряму її не змінюють. Створіть чернетку-копію, правте її і опублікуйте.'
              : 'Архівна версія — лише для перегляду й порівняння. Її можна скопіювати в нову чернетку.'}
          </p>
          <Button onClick={onCreateDraft}>Створити чернетку з цієї версії</Button>
        </div>
      ) : null}

      <div className="grid items-start gap-4 min-[1440px]:grid-cols-[minmax(0,1fr)_20rem]">
        <div className="flex min-w-0 flex-col gap-4">
          <div className="-mx-1 border-b border-separator bg-canvas px-1 pt-1 pb-3 lg:sticky lg:top-[var(--sticky-top)] lg:z-20">
            <EditorToolbar
              view={view}
              onViewChange={(next) => {
                onViewChange(next);
                setFocus(null);
              }}
              filters={filters}
              onFiltersChange={onFiltersChange}
              departments={departments}
              shown={visible.length}
              total={entities.length}
              searchRef={searchRef}
              editable={editable}
              canUndo={editing.canUndo}
              canRedo={editing.canRedo}
              undoLabel={editing.undoLabel}
              redoLabel={editing.redoLabel}
              onUndo={() => void editing.undo()}
              onRedo={() => void editing.redo()}
              onAdd={() => openAdd()}
            />
            {!wide ? (
              <Button
                size="sm"
                variant="ghost"
                className="mt-2"
                onClick={() => setPanelOpen(true)}
                iconStart={<PanelRightOpen aria-hidden="true" className="size-4" />}
              >
                Конфлікти: {conflictsQuery.data?.hard.length ?? version.hardConflicts} жорстких · {conflictsQuery.data?.soft.length ?? version.softWarnings} попереджень
              </Button>
            ) : null}
          </div>

          {loadError ? (
            <ErrorState
              error={loadError}
              title="Сітку не завантажено"
              onRetry={() => {
                catalog.refetch();
                if (lessonsQuery.error) void lessonsQuery.refetch();
                if (windowsQuery.error) void windowsQuery.refetch();
              }}
              className="rounded-panel border border-default bg-surface"
            />
          ) : loading ? (
            <BoardSkeleton />
          ) : (
            <>
              {lessons.length === 0 ? (
                <EmptyState
                  align="start"
                  size="sm"
                  title="У версії ще немає жодної пари"
                  description="Клацніть порожню клітинку групи, щоб додати пару, або згенеруйте розклад з навантаження."
                  action={
                    editable ? (
                      <Button size="sm" variant="primary" onClick={() => openAdd()} iconStart={<Plus aria-hidden="true" className="size-4" />}>
                        Додати пару
                      </Button>
                    ) : undefined
                  }
                  secondaryAction={
                    <Link to="/admin/generation" className={buttonClass({ size: 'sm' })}>
                      <Cpu aria-hidden="true" className="size-3.5" />
                      До генератора
                    </Link>
                  }
                  className="rounded-panel border border-default bg-surface"
                />
              ) : null}
              {visible.length === 0 ? (
                <EmptyState
                  title="За фільтрами нікого не знайдено"
                  description={
                    view === 'group'
                      ? 'Перевірте код групи або курс. Пошук розуміє і латиницю: 4ki1.'
                      : 'Викладачі й аудиторії зʼявляються тут, лише якщо мають пари в цій версії.'
                  }
                  action={
                    <Button size="sm" onClick={() => onFiltersChange(EMPTY_FILTERS)}>
                      Скинути фільтри
                    </Button>
                  }
                  className="rounded-panel border border-default bg-surface"
                />
              ) : (
                <TimetableBoard
                  versionId={version.id}
                  view={view}
                  entities={visible}
                  lessons={lessons}
                  windows={windows}
                  rooms={roomsById}
                  groupShift={groupShift}
                  editable={editable}
                  conflictsByLesson={conflictsByLesson}
                  selectedLessonId={selectedId}
                  focus={focus}
                  onSelect={(lesson) => setSelectedId(lesson.id)}
                  onOpen={openEdit}
                  onAdd={openAdd}
                  onMove={(lesson, target, room) => void editing.moveLesson(lesson, target, room)}
                  onRejected={(_lesson, _target, conflicts) => editing.reportRejected(conflicts)}
                />
              )}
            </>
          )}
        </div>

        {wide ? (
          <aside aria-label="Деталі й конфлікти" className="sticky top-[calc(var(--sticky-top)+1rem)] flex max-h-[calc(100dvh-var(--sticky-top)-2rem)] min-h-0 flex-col gap-4">
            {sidePanel}
          </aside>
        ) : null}
      </div>

      {!wide ? (
        <Drawer direction="right" open={panelOpen} onOpenChange={setPanelOpen}>
          <DrawerContent side="right" title="Конфлікти й вибрана пара" className="max-w-sm">
            <div className="flex h-full min-h-0 flex-col gap-4">{sidePanel}</div>
          </DrawerContent>
        </Drawer>
      ) : null}

      {!wide && selected ? (
        <div className={cn('fixed inset-x-3 bottom-3 z-30 flex items-center gap-3 rounded-panel border border-default bg-raised px-3 py-2 shadow-popover', 'sm:start-auto sm:w-[28rem]')}>
          <p className="min-w-0 flex-1 truncate text-ui text-primary">{lessonTitle(selected)}</p>
          {editable ? (
            <>
              <Button size="sm" onClick={() => openEdit(selected)}>
                Змінити
              </Button>
              <Button size="sm" onClick={() => setMoving(selected)}>
                Перемістити
              </Button>
            </>
          ) : null}
        </div>
      ) : null}

      {catalog.data ? (
        <LessonDialog
          open={lessonDialog !== null}
          onOpenChange={(open) => !open && setLessonDialog(null)}
          lesson={lessonDialog?.lesson ?? null}
          initial={lessonDialog?.initial ?? EMPTY_LESSON_FORM}
          catalog={catalog.data}
          lessons={lessons}
          windows={windows}
          onSubmit={async (body) => {
            const saved = lessonDialog?.lesson
              ? await editing.updateLesson(lessonDialog.lesson, body)
              : await editing.createLesson(body);
            if (saved) setSelectedId(saved.id);
          }}
        />
      ) : null}
      <MoveDialog
        versionId={version.id}
        lesson={moving}
        onOpenChange={(open) => !open && setMoving(null)}
        rooms={catalog.data?.rooms ?? []}
        lessons={lessons}
        onMove={editing.moveLesson}
      />
    </div>
  );
}

/** Силует дошки: дві сітки з рядками пар, поки вантажаться довідники й заняття. */
function BoardSkeleton() {
  return (
    <LoadingRegion label="Завантажуємо сітку розкладу…" className="flex flex-col gap-4">
      {[0, 1].map((grid) => (
        <div key={grid} className="rounded-panel border border-default bg-surface">
          <div className="flex items-center gap-3 border-b border-separator px-3 py-2.5">
            <Skeleton className="h-6 w-14" />
            <Skeleton className="h-4 w-28" />
          </div>
          <div className="grid grid-cols-[2.75rem_repeat(5,minmax(0,1fr))] gap-1 p-1">
            {Array.from({ length: 24 }, (_, cell) => (
              <Skeleton key={cell} className={cn('h-14', cell % 6 === 0 && 'w-8 justify-self-center')} />
            ))}
          </div>
        </div>
      ))}
    </LoadingRegion>
  );
}
