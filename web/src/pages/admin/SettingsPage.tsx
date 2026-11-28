import { Link } from 'react-router';
import { usePublicMeta } from '@/api/meta';
import { useSettings } from '@/api/settings';
import { AiInfo, NumberingInfo } from '@/components/admin-daily/settings/ReadOnlyBlocks';
import { SettingsForm } from '@/components/admin-daily/settings/SettingsForm';
import { LoadingRegion, PageErrorBanner, PageHeader, Panel, Skeleton, SkeletonText } from '@/components/ui';
import { cn } from '@/lib/cn';

function FormSkeleton() {
  return (
    <LoadingRegion label="Завантажуємо налаштування…" className="flex flex-col gap-8">
      {[0, 1, 2].map((key) => (
        <div key={key} className="grid gap-6 lg:grid-cols-[17rem_minmax(0,1fr)]">
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
  const semester = meta.data?.semester ?? undefined;

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Налаштування"
        meta={
          // одним абзацом: рядок контексту — flex-контейнер, і окремий текстовий вузол «.» став би своїм елементом
          // з проміжком перед ним; посилання з крапкою не розриваються
          <span>
            Зміни діють одразу після збереження. Листи на пошту —{' '}
            <span className="whitespace-nowrap">
              <Link to="/admin/notifications" className="font-[650] text-accent underline-offset-2 hover:underline">
                у «Сповіщеннях»
              </Link>
              .
            </span>
          </span>
        }
      />
      {/*
        Форма живе в admin-daily; тут лише розкладка (DESIGN §11.4):
        - опис розділу над полями до 1024 px, а не вузькою колонкою поруч (інакше поля по 160 px і «Заступник директор|»);
        - у перемикачах пояснення лише під вибраним варіантом — решта читається з назви, без стіни тексту.
      */}
      <Panel
        className={cn(
          'max-lg:[&_section]:grid-cols-1',
          '[&_[role=radio][data-state=unchecked]~span>span:nth-child(2)]:hidden',
        )}
      >
        {settings.isPending ? (
          <FormSkeleton />
        ) : settings.isError ? (
          <PageErrorBanner error={settings.error} title="Налаштування не завантажено" retrying={settings.isFetching} onRetry={() => void settings.refetch()} />
        ) : (
          <SettingsForm settings={settings.data} numbering={<NumberingInfo semester={semester} />} ai={<AiInfo />} />
        )}
      </Panel>
    </div>
  );
}
