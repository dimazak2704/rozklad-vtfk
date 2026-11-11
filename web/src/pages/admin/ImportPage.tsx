import { useSearchParams } from 'react-router';
import type { ImportJob } from '@/api/import';
import { Disclosure } from '@/components/generation/Disclosure';
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
    <div className="flex flex-col gap-6">
      {/* у завданні шлях назад — до списку імпортів, і його видно завжди: бічна панель веде лише на сторінку */}
      <PageHeader title="Імпорт" back={jobId ? { to: '/admin/import', label: 'Усі імпорти' } : undefined} />
      {jobId ? (
        <JobReview key={jobId} jobId={jobId} />
      ) : (
        <div className="grid gap-8 xl:grid-cols-[minmax(0,1fr)_19rem]">
          <div className="flex min-w-0 flex-col gap-8">
            <UploadPanel onUploaded={open} />
            <Section title="Завдання імпорту" headingLevel={2}>
              <JobList onOpen={open} />
            </Section>
          </div>
          <aside className="flex flex-col gap-4" aria-label="Довідка про імпорт">
            <AiUsageWidget />
            <Disclosure summary="Що можна імпортувати?">
              <dl className="flex flex-col gap-2.5 ps-5.5">
                {KINDS.map((kind) => (
                  <div key={kind} className="flex flex-col gap-0.5">
                    <dt className="text-caption font-[650] text-primary">{IMPORT_KIND_LABEL[kind]}</dt>
                    <dd className="text-caption text-secondary">{IMPORT_KIND_HINT[kind]}</dd>
                  </div>
                ))}
                <p className="text-caption text-muted">Імпорт нічого не змінює, доки ви не натиснете «Застосувати».</p>
              </dl>
            </Disclosure>
          </aside>
        </div>
      )}
    </div>
  );
}
