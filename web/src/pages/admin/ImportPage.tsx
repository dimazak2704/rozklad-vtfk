import { useSearchParams } from 'react-router';
import type { ImportJob } from '@/api/import';
import { AiUsageWidget } from '@/components/import/AiUsageWidget';
import { JobList } from '@/components/import/JobList';
import { JobReview } from '@/components/import/JobReview';
import { IMPORT_KIND_HINT, IMPORT_KIND_LABEL } from '@/components/import/labels';
import { UploadPanel } from '@/components/import/UploadPanel';
import { PageHeader, Section } from '@/components/ui';

const KINDS = ['TIMETABLE_XLSX', 'DISCIPLINE_PROGRAM', 'CURRICULUM', 'ORDER_DOCUMENT'] as const;

/**
 * Імпорт: завантаження таблиць, документів і фото → чернетка зі звітом → рішення щодо незнайомих назв →
 * застосування. Перегляд завдання — у тій самій сторінці (`?job=`), тож посиланням можна поділитися.
 */
export default function ImportPage() {
  const [params, setParams] = useSearchParams();
  const jobId = params.get('job');

  const open = (job: Pick<ImportJob, 'id'>) =>
    setParams((prev) => {
      const next = new URLSearchParams(prev);
      next.set('job', job.id);
      return next;
    });

  return (
    <div className="flex flex-col gap-5">
      <PageHeader
        title="Імпорт"
        description="Таблиця розкладу, програми дисциплін, навчальний план і фото розпоряджень → перевірена чернетка → дані системи."
      />
      {jobId ? (
        <JobReview key={jobId} jobId={jobId} backTo="/admin/import" />
      ) : (
        <div className="grid gap-6 xl:grid-cols-[minmax(0,1fr)_19rem]">
          <div className="flex min-w-0 flex-col gap-6">
            <UploadPanel onUploaded={open} />
            <Section title="Завдання імпорту" headingLevel={2} description="Нові зверху. Стан оновлюється сам, поки сервер розбирає чи застосовує файл.">
              <JobList onOpen={open} />
            </Section>
          </div>
          <aside className="flex flex-col gap-4" aria-label="Довідка про імпорт">
            <AiUsageWidget />
            <section className="flex flex-col gap-2 rounded-panel border border-default bg-surface p-4">
              <h2 className="text-ui font-semibold text-primary">Що можна імпортувати</h2>
              <dl className="flex flex-col gap-2.5">
                {KINDS.map((kind) => (
                  <div key={kind} className="flex flex-col gap-0.5">
                    <dt className="text-caption font-semibold text-primary">{IMPORT_KIND_LABEL[kind]}</dt>
                    <dd className="text-caption text-secondary">{IMPORT_KIND_HINT[kind]}</dd>
                  </div>
                ))}
              </dl>
              <p className="border-t border-separator pt-2 text-caption text-muted">
                Імпорт нічого не змінює, доки ви не натиснете «Застосувати».
              </p>
            </section>
          </aside>
        </div>
      )}
    </div>
  );
}
