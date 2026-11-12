import { Cpu, Lock, PanelRightOpen, Plus } from 'lucide-react';
import { useMemo, useRef, useState } from 'react';
import { Link } from 'react-router';
import {
  usePlanningCatalog,
  useVersionConflicts,
  useVersionLessons,
  useVersionMetrics,
  type TimetableConflict,
} from '@/api/timetable';
import { useShiftWindows } from '@/api/calendar';
import type { MetricsDto, Parity, TemplateLessonDto, TimetableVersionDto, Uuid, Weekday } from '@/api/types';
import { Button, buttonClass, Drawer, DrawerContent, EmptyState, LoadingRegion, PageErrorBanner, Skeleton, type MoreMenuItem } from '@/components/ui';
import { isModalOpen, useHotkeys, useShortcutHints } from '@/hooks/useHotkeys';
import { cn } from '@/lib/cn';
import { useRegisterCommands } from '@/stores/commands';
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
  /** Запит «показати пару» ззовні (з порівняння версій). */
  initialFocusLessonId: Uuid | null;
  /** Телефон: дії версії зі сторінки — в одному «⋯» поруч із пошуком (заголовок тоді без кнопок). */
  phoneMenu?: readonly MoreMenuItem[];
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
  initialFocusLessonId,
  phoneMenu = [],
}: TimetableEditorProps) {
  const editable = version.status === 'DRAFT';
  const wide = useMediaQuery('(min-width: 1440px)');
  // на телефоні пʼять днів у 350 px — це «Вст.с…»; там сітка показує один день з перемикачем
  const phone = useMediaQuery('(max-width: 767px)');
  const [day, setDay] = useState<Weekday>(() => {
    const today = new Date().getDay();
    return (today >= 1 && today <= 5 ? today : 1) as Weekday;
  });
  const catalog = usePlanningCatalog();
  const windowsQuery = useShiftWindows();
  const lessonsQuery = useVersionLessons(version.id);
  const conflictsQuery = useVersionConflicts(version.id);
  const metricsQuery = useVersionMetrics(version.id);
  const editing = useLessonEditing(version.id, {
    // Ctrl+Z повернув видалену пару — фокус і вибір на ній (якщо поверх не відкрите вікно, напр. журнал)
    onRestored: (lesson) => {
      if (isModalOpen()) return;
      setSelectedId(lesson.id);
      setFocus((prev) => ({ entityId: null, lessonId: lesson.id, weekday: lesson.weekday, pair: lesson.pair, nonce: (prev?.nonce ?? 0) + 1 }));
    },
  });
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
    () =>
      [...new Set((catalog.data?.groups ?? []).flatMap((g) => (g.department ? [g.department] : [])))].sort((a, b) => a.localeCompare(b, 'uk')),
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

  // без «Ви впевнені?»: видалення з чернетки скасовується зі сповіщення або Ctrl+Z
  const removeLesson = async (lesson: TemplateLessonDto) => {
    // фокус не губиться на зниклій парі: одразу на її слот (сусідню пару чи «Додати»)
    const hadFocus = document.activeElement?.closest(`[data-lesson-id="${lesson.id}"]`) != null || selectedId === lesson.id;
    const entityId = entityIdOf(view, lesson);
    if (hadFocus && entityId) {
      setFocus({ entityId, lessonId: null, skipLessonId: lesson.id, weekday: lesson.weekday, pair: lesson.pair, nonce: Date.now() });
    }
    if (await editing.deleteLesson(lesson)) setSelectedId(null);
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
    // Ctrl+Z / Ctrl+Shift+Z — спільні для панелі (JournalHost): журнал знає, що це правки саме цієї версії
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

  // стрілки, Пробіл і F2 обробляє сама сітка — у довідці вони мають бути теж
  useShortcutHints(
    editable
      ? [
          { keys: '←↑→↓', description: 'Перейти до сусідньої пари чи слота', group: 'Базовий розклад' },
          { keys: 'space,enter', description: 'Підхопити пару; стрілки — новий слот; ще раз — поставити', group: 'Базовий розклад' },
          { keys: 'f2', description: 'Змінити пару, на якій фокус', group: 'Базовий розклад' },
        ]
      : [],
  );

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
        softWarnings={conflictsQuery.data?.soft.length ?? version.softWarnings}
        loading={metricsQuery.isPending}
        publishable={editable}
        onShowConflicts={() => {
          setConflictTab('HARD');
          if (!wide) setPanelOpen(true);
        }}
      />

      {!editable ? (
        // дія «Створити чернетку» — головна кнопка в заголовку; тут лише пояснення, чому сітка не тягається
        <p className="flex max-w-prose items-start gap-2 text-ui text-secondary">
          <Lock aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-muted" />
          <span>
            {version.status === 'PUBLISHED'
              ? 'Ця версія опублікована й діє, тому лише для перегляду. Щоб змінити розклад, створіть чернетку.'
              : 'Архівна версія — лише для перегляду й порівняння.'}
          </span>
        </p>
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
              day={phone ? day : null}
              onDayChange={setDay}
              compact={phone}
              extraMenu={phoneMenu}
            />
          </div>

          {loadError ? (
            <PageErrorBanner
              error={loadError}
              title="Сітку не завантажено"
              retrying={lessonsQuery.isFetching || windowsQuery.isFetching}
              onRetry={() => {
                catalog.refetch();
                if (lessonsQuery.error) void lessonsQuery.refetch();
                if (windowsQuery.error) void windowsQuery.refetch();
              }}
            />
          ) : loading ? (
            <BoardSkeleton />
          ) : (
            <>
              {lessons.length === 0 ? (
                <EmptyState
                  align="start"
                  size="sm"
                  title="У версії ще немає жодної пари."
                  description={editable ? 'Клацніть порожню клітинку групи, щоб додати пару.' : undefined}
                  action={
                    <Link to="/admin/generation" className={buttonClass({ variant: 'ghost', size: 'sm' })}>
                      <Cpu aria-hidden="true" className="size-3.5" />
                      Згенерувати з навантаження
                    </Link>
                  }
                />
              ) : null}
              {visible.length === 0 ? (
                <EmptyState
                  size="sm"
                  title={filters.query ? `«${filters.query}» не знайдено.` : 'За фільтром нікого немає.'}
                  description={
                    view === 'group'
                      ? 'Пошук розуміє і латиницю: 4ki1.'
                      : 'Викладачі й аудиторії зʼявляються тут, лише якщо мають пари в цій версії.'
                  }
                  action={
                    <Button size="sm" variant="ghost" onClick={() => onFiltersChange(EMPTY_FILTERS)}>
                      Показати всіх
                    </Button>
                  }
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
                  days={phone ? [day] : undefined}
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
          ) : (
            <Button size="sm" onClick={() => setPanelOpen(true)} iconStart={<PanelRightOpen aria-hidden="true" className="size-4" />}>
              Подробиці
            </Button>
          )}
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
