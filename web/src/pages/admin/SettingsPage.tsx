import { usePublicMeta } from '@/api/meta';
import { useSettings } from '@/api/settings';
import { AiInfo, NumberingInfo } from '@/components/admin-daily/settings/ReadOnlyBlocks';
import { SettingsForm } from '@/components/admin-daily/settings/SettingsForm';
import { ErrorState, LoadingRegion, PageHeader, Panel, Skeleton, SkeletonText } from '@/components/ui';

function FormSkeleton() {
  return (
    <LoadingRegion label="Завантажуємо налаштування…" className="flex flex-col gap-8">
      {[0, 1, 2].map((key) => (
        <div key={key} className="grid gap-6 md:grid-cols-[17rem_minmax(0,1fr)]">
          <SkeletonText lines={2} />
          <div className="flex flex-col gap-3">
            <Skeleton className="h-4 w-40" />
            <Skeleton className="h-9 w-full max-w-md" />
            <Skeleton className="h-4 w-56" />
            <Skeleton className="h-9 w-full max-w-md" />
          </div>
        </div>
      ))}
    </LoadingRegion>
  );
}

/** Налаштування системи: погодження замін, підписант розпорядження, дзвінки, публічний сайт, ШІ. */
export default function SettingsPage() {
  const settings = useSettings();
  const meta = usePublicMeta();
  const semester = meta.data ? { startsOn: meta.data.semester.startsOn, endsOn: meta.data.semester.endsOn } : undefined;

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Налаштування"
        description="Правила, за якими працюють заміни й розпорядження. Зміни діють одразу після збереження."
      />
      <Panel>
        {settings.isPending ? (
          <FormSkeleton />
        ) : settings.isError ? (
          <ErrorState error={settings.error} onRetry={() => void settings.refetch()} />
        ) : (
          <SettingsForm settings={settings.data} numbering={<NumberingInfo semester={semester} />} ai={<AiInfo />} />
        )}
      </Panel>
    </div>
  );
}
