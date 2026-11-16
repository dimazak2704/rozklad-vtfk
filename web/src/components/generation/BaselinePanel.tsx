import { useGenerationJobs } from '@/api/generation';
import type { TimetableVersionDto } from '@/api/types';
import { cn } from '@/lib/cn';
import { baselineSentence, solverHardOfVersion } from './model';

/** Стан базової версії до запуску. */
export function BaselinePanel({ version }: { version: TimetableVersionDto }) {
  // жорсткі порушення за правилами генератора — з оцінки бази в останньому запуску (той самий запит, що й історія)
  const jobs = useGenerationJobs(version.semesterId);
  const solverHard = solverHardOfVersion(jobs.data ?? [], version.id);
  const hasHard = version.hardConflicts > 0 || (solverHard ?? 0) > 0;
  return (
    <section aria-labelledby="baseline-title" className="flex flex-col gap-1">
      <h2 id="baseline-title" className="text-heading font-bold text-primary">
        Зараз у «{version.name}»
      </h2>
      {/* жорсткі порушення — саме те, заради чого запускають генератор: тоді речення в кольорі скасування */}
      <p className={cn('text-body', hasHard ? 'font-[600] text-cancel' : 'text-secondary')}>{baselineSentence(version, solverHard)}</p>
    </section>
  );
}
