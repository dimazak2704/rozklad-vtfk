import { Cpu, Upload } from 'lucide-react';
import { useState } from 'react';
import { Link } from 'react-router';
import { usePublicMeta } from '@/api/meta';
import { useTimetableVersions, useVersionConflicts } from '@/api/timetable';
import type { TemplateLessonDto, Uuid } from '@/api/types';
import { buttonClass, EmptyState, ErrorState, LoadingRegion, PageHeader, Skeleton } from '@/components/ui';
import { CreateVersionDialog } from '@/components/timetable/dialogs/CreateVersionDialog';
import { PublishDialog } from '@/components/timetable/dialogs/PublishDialog';
import { DiffView } from '@/components/timetable/DiffView';
import { EMPTY_FILTERS } from '@/components/timetable/model/grid';
import { pickVersion, publishedVersion } from '@/components/timetable/model/versions';
import { TimetableEditor } from '@/components/timetable/TimetableEditor';
import { useEditorUrlState } from '@/components/timetable/useEditorUrlState';
import { VersionBar } from '@/components/timetable/VersionBar';
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
  const conflicts = useVersionConflicts(publishOpen ? selected?.id : undefined);

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

  const header = (
    <PageHeader
      title="Базовий розклад"
      description="Тижневий шаблон чисельника й знаменника для всіх груп: перетягуйте пари, стежте за конфліктами, порівнюйте версії й публікуйте з потрібної дати."
    />
  );

  if (meta.error || versions.error) {
    return (
      <div className="flex flex-col gap-6">
        {header}
        <ErrorState
          error={meta.error ?? versions.error}
          title="Версії розкладу не завантажено"
          onRetry={() => void (meta.error ? meta.refetch() : versions.refetch())}
          className="rounded-panel border border-default bg-surface"
        />
      </div>
    );
  }

  if (!semester || versions.isPending) {
    return (
      <div className="flex flex-col gap-6">
        {header}
        <LoadingRegion label="Завантажуємо версії розкладу…" className="flex flex-col gap-4">
          <Skeleton className="h-14 w-full rounded-panel" />
          <Skeleton className="h-16 w-full rounded-panel" />
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
          title={`Для «${semester.name}» ще немає жодної версії розкладу`}
          description="Імпортуйте розклад з Excel коледжу, створіть порожню версію й заповніть її вручну або згенеруйте з навантаження."
          action={
            <button type="button" className={buttonClass({ variant: 'primary' })} onClick={() => setCreateRequest({ copyFromId: null })}>
              Створити версію
            </button>
          }
          secondaryAction={
            <span className="flex flex-wrap gap-2">
              <Link to="/admin/import" className={buttonClass()}>
                <Upload aria-hidden="true" className="size-4" />
                Імпорт з Excel
              </Link>
              <Link to="/admin/generation" className={buttonClass()}>
                <Cpu aria-hidden="true" className="size-4" />
                Генератор
              </Link>
            </span>
          }
          className="rounded-panel border border-default bg-surface"
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
      <VersionBar
        versions={list}
        selected={selected}
        onSelect={(versionId) => setUrl({ versionId, mode: url.mode })}
        mode={url.mode}
        onModeChange={(mode) => setUrl({ mode })}
        onCreate={() => setCreateRequest({ copyFromId: selected.id })}
        onPublish={() => setPublishOpen(true)}
      />

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
          onCreateDraft={() => setCreateRequest({ copyFromId: selected.id })}
          initialFocusLessonId={focusLessonId}
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
        onShowConflicts={() => setUrl({ mode: 'grid', filters: { ...EMPTY_FILTERS, conflictsOnly: true } })}
      />
    </div>
  );
}
