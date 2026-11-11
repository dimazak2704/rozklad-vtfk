import type { TimetableVersionDto } from '@/api/types';
import { baselineSentence } from './model';

/** Стан базової версії до запуску. */
export function BaselinePanel({ version }: { version: TimetableVersionDto }) {
  return (
    <section aria-labelledby="baseline-title" className="flex flex-col gap-1">
      <h2 id="baseline-title" className="text-heading font-bold text-primary">
        Зараз у «{version.name}»
      </h2>
      <p className="text-body text-secondary">{baselineSentence(version)}</p>
    </section>
  );
}
