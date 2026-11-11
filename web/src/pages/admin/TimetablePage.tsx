import { CopyPlus, Cpu, FileDiff, FilePlus, Send, TriangleAlert, Upload } from 'lucide-react';
import { useState } from 'react';
import { Link } from 'react-router';
import { usePublicMeta } from '@/api/meta';
import { useTimetableVersions, useVersionConflicts } from '@/api/timetable';
import type { TemplateLessonDto, Uuid } from '@/api/types';
import { Button, buttonClass, EmptyState, LoadingRegion, PageErrorBanner, PageHeader, Skeleton, type MoreMenuItem } from '@/components/ui';
import { CreateVersionDialog } from '@/components/timetable/dialogs/CreateVersionDialog';
import { PublishDialog } from '@/components/timetable/dialogs/PublishDialog';
import { DiffView } from '@/components/timetable/DiffView';
import { EMPTY_FILTERS } from '@/components/timetable/model/grid';
import { pickVersion, publishedVersion, versionSentence } from '@/components/timetable/model/versions';
import { TimetableEditor } from '@/components/timetable/TimetableEditor';
import { useEditorUrlState } from '@/components/timetable/useEditorUrlState';
import { ModeSwitch, VersionSelect } from '@/components/timetable/VersionBar';
import { useMediaQuery } from '@/hooks/useMediaQuery';
import { todayInKyiv } from '@/lib/date';
import { useRegisterCommands } from '@/stores/commands';

/**
 * Редактор базового розкладу: версії семестру (чернетки, опублікована, архів), сітка з перетягуванням,
 * конфлікти й метрики, порівняння версій і публікація з датою введення.
 */
export default function TimetablePage() {
  const meta = usePublicMeta();
  const semester = meta.data?.semester;
  const versions = useTimetableVersions(semester?.id);
  const [url, setUrl] = useEditorUrlState();
  const [createRequest, setCreateRequest] = useState<{ copyFromId: Uuid | null } | null>(null);
  const [publishOpen, setPublishOpen] = useState(false);
  const [focusLessonId, setFocusLessonId] = useState<Uuid | null>(null);
  const [editorKey, setEditorKey] = useState(0);

  const list = versions.data ?? [];
  const selected = pickVersion(list, url.versionId);
  const published = publishedVersion(list);
  // той самий запит, що й у редакторі (кеш спільний): від жорстких конфліктів залежить головна дія
  const conflicts = useVersionConflicts(selected?.id);
  const hardCount = conflicts.data?.hard.length ?? selected?.hardConflicts ?? 0;
  const blocked = selected?.status === 'DRAFT' && hardCount > 0;
  // на телефоні редактор — для перегляду: дії версії переходять у єдине «⋯» біля пошуку
  const phone = useMediaQuery('(max-width: 767.98px)');

  useRegisterCommands(
    selected
      ? [
          {
            id: 'new-version',
            label: 'Нова версія розкладу (копія поточної)',
            group: 'Базовий розклад',
            perform: () => setCreateRequest({ copyFromId: selected.id }),
          },
          ...(selected.status === 'DRAFT'
            ? [{ id: 'publish-version', label: `Опублікувати «${selected.name}»`, group: 'Базовий розклад', perform: () => setPublishOpen(true) }]
            : []),
          {
            id: 'compare-versions',
            label: 'Порівняти з опублікованою версією',
            group: 'Базовий розклад',
            perform: () => setUrl({ mode: 'diff' }),
          },
        ]
      : [],
  );

  const showInGrid = (lesson: TemplateLessonDto) => {
    setFocusLessonId(lesson.id);
    setEditorKey((key) => key + 1);
    setUrl({ mode: 'grid', view: 'group', filters: { ...EMPTY_FILTERS, query: lesson.group.code } });
  };

  const showConflicts = () => setUrl({ mode: 'grid', filters: { ...EMPTY_FILTERS, conflictsOnly: true } });

  // головна дія одна: для чернетки — опублікувати (а поки є жорсткі конфлікти — показати їх, бо публікація
  // однаково неможлива), для опублікованої чи архівної — почати чернетку з неї
  const primaryAction = selected ? (
    selected.status === 'DRAFT' ? (
      blocked ? (
        <Button variant="primary" onClick={showConflicts} iconStart={<TriangleAlert aria-hidden="true" className="size-4" />}>
          Показати конфлікти
        </Button>
      ) : (
        <Button variant="primary" onClick={() => setPublishOpen(true)} iconStart={<Send aria-hidden="true" className="size-4" />}>
          Опублікувати
        </Button>
      )
    ) : (
      <Button variant="primary" onClick={() => setCreateRequest({ copyFromId: selected.id })} iconStart={<CopyPlus aria-hidden="true" className="size-4" />}>
        Створити чернетку
      </Button>
    )
  ) : undefined;

  const more: MoreMenuItem[] = selected
    ? [
        ...(blocked
          ? [
              {
                label: 'Опублікувати (є конфлікти)',
                icon: <Send aria-hidden="true" className="size-4" />,
                disabled: true,
              },
            ]
          : []),
        ...(url.mode === 'grid'
          ? [{ label: 'Порівняти з іншою версією', icon: <FileDiff aria-hidden="true" className="size-4" />, onSelect: () => setUrl({ mode: 'diff' }) }]
          : []),
        ...(selected.status === 'DRAFT'
          ? [
              {
                label: 'Нова версія (копія цієї)',
                icon: <CopyPlus aria-hidden="true" className="size-4" />,
                onSelect: () => setCreateRequest({ copyFromId: selected.id }),
                separatorBefore: true,
              },
            ]
          : []),
        {
          label: 'Порожня версія',
          icon: <FilePlus aria-hidden="true" className="size-4" />,
          onSelect: () => setCreateRequest({ copyFromId: null }),
          separatorBefore: selected.status !== 'DRAFT',
        },
        { label: 'Імпорт з Excel', icon: <Upload aria-hidden="true" className="size-4" />, to: '/admin/import', separatorBefore: true },
        { label: 'Генератор', icon: <Cpu aria-hidden="true" className="size-4" />, to: '/admin/generation' },
      ]
    : [];

  // телефон у сітці: головна дія й «⋯» — у меню біля пошуку, а тут лише назва й версія
  const phoneGrid = phone && url.mode === 'grid' && Boolean(selected);
  const phoneMenu: MoreMenuItem[] = phoneGrid && selected
    ? [
        ...(selected.status === 'DRAFT'
          ? blocked
            ? [{ label: 'Показати конфлікти', icon: <TriangleAlert aria-hidden="true" className="size-4" />, onSelect: showConflicts }]
            : [{ label: 'Опублікувати', icon: <Send aria-hidden="true" className="size-4" />, onSelect: () => setPublishOpen(true) }]
          : [{ label: 'Створити чернетку', icon: <CopyPlus aria-hidden="true" className="size-4" />, onSelect: () => setCreateRequest({ copyFromId: selected.id }) }]),
        ...more,
      ]
    : [];

  const header = (
    <PageHeader
      title="Базовий розклад"
      meta={selected && !phone ? versionSentence(selected) : undefined}
      controls={
        selected ? (
          <>
            <VersionSelect versions={list} selected={selected} onSelect={(versionId) => setUrl({ versionId, mode: url.mode })} />
            {/* «Порівняння» живе в «⋯»; перемикач видно лише в самому порівнянні — щоб було як повернутись до сітки */}
            {url.mode === 'diff' ? <ModeSwitch mode={url.mode} onModeChange={(mode) => setUrl({ mode })} /> : null}
          </>
        ) : undefined
      }
      primaryAction={phoneGrid ? undefined : primaryAction}
      more={phoneGrid ? undefined : more}
    />
  );

  if (meta.error || versions.error) {
    return (
      <div className="flex flex-col gap-6">
        {header}
        <PageErrorBanner
          error={meta.error ?? versions.error}
          title="Версії розкладу не завантажено"
          retrying={meta.isFetching || versions.isFetching}
          onRetry={() => void (meta.error ? meta.refetch() : versions.refetch())}
        />
      </div>
    );
  }

  if (!semester || versions.isPending) {
    return (
      <div className="flex flex-col gap-6">
        {header}
        <LoadingRegion label="Завантажуємо версії розкладу…" className="flex flex-col gap-4">
          <Skeleton className="h-5 w-2/3 max-w-xl" />
          <Skeleton className="h-11 w-full max-w-2xl rounded-control" />
          <Skeleton className="h-96 w-full rounded-panel" />
        </LoadingRegion>
      </div>
    );
  }

  if (!selected) {
    return (
      <div className="flex flex-col gap-6">
        {header}
        <EmptyState
          title={`Для «${semester.name}» ще немає розкладу.`}
          description="Найшвидше — імпортувати розклад коледжу з Excel. Можна й створити порожню версію або згенерувати з навантаження."
          action={
            <Link to="/admin/import" className={buttonClass({ variant: 'primary' })}>
              <Upload aria-hidden="true" className="size-4" />
              Імпорт з Excel
            </Link>
          }
          secondaryAction={
            <span className="flex flex-wrap gap-2">
              <button type="button" className={buttonClass()} onClick={() => setCreateRequest({ copyFromId: null })}>
                Порожня версія
              </button>
              <Link to="/admin/generation" className={buttonClass({ variant: 'ghost' })}>
                <Cpu aria-hidden="true" className="size-4" />
                Генератор
              </Link>
            </span>
          }
        />
        <CreateVersionDialog
          request={createRequest}
          onOpenChange={(open) => !open && setCreateRequest(null)}
          semesterId={semester.id}
          versions={list}
          onCreated={(version) => setUrl({ versionId: version.id, mode: 'grid' })}
        />
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-4">
      {header}

      {url.mode === 'diff' ? (
        <DiffView
          versions={list}
          target={selected}
          baseId={url.baseId}
          onBaseChange={(baseId) => setUrl({ baseId })}
          onShowInGrid={showInGrid}
        />
      ) : (
        <TimetableEditor
          key={`${selected.id}-${editorKey}`}
          version={selected}
          baseline={published && published.id !== selected.id ? published.metrics : null}
          view={url.view}
          onViewChange={(view) => setUrl({ view })}
          filters={url.filters}
          onFiltersChange={(filters) => setUrl({ filters })}
          initialFocusLessonId={focusLessonId}
          phoneMenu={phoneMenu}
        />
      )}

      <CreateVersionDialog
        request={createRequest}
        onOpenChange={(open) => !open && setCreateRequest(null)}
        semesterId={semester.id}
        versions={list}
        onCreated={(version) => setUrl({ versionId: version.id, mode: 'grid' })}
      />
      <PublishDialog
        version={publishOpen ? selected : null}
        onOpenChange={setPublishOpen}
        semester={semester}
        today={meta.data?.today ?? todayInKyiv()}
        hardConflicts={conflicts.data?.hard}
        onShowConflicts={showConflicts}
      />
    </div>
  );
}
