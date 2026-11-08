import { FileImage, FileSpreadsheet, FileText, Upload, X } from 'lucide-react';
import { useId, useRef, useState, type DragEvent } from 'react';
import { activeSemesterOf, useSemesters } from '@/api/calendar';
import { IMPORT_OPTION_LIMITS, useUploadImport, type ImportJob, type ImportOptions } from '@/api/import';
import type { ImportKind } from '@/api/types';
import { applyServerErrors } from '@/components/catalog/forms';
import { FormAlert } from '@/components/catalog/FormParts';
import { Button, Field, IconButton, Input, NumberInput, Select, notify } from '@/components/ui';
import { cn } from '@/lib/cn';
import { ACCEPT_ATTRIBUTE, detectImportKind, extensionOf, fileProblem, formatBytes, kindAccepts, usesVision } from './detect';
import { GroupCodesPicker } from './GroupCodesPicker';
import { IMPORT_KIND_LABEL, IMPORT_KIND_OPTIONS } from './labels';

interface UploadPanelProps {
  onUploaded: (job: ImportJob) => void;
}

interface Picked {
  file: File;
  kind: ImportKind;
  reason: string;
}

/** Іконка за форматом файлу. */
export function FileIcon({ name, className }: { name: string; className?: string }) {
  const ext = extensionOf(name);
  const Icon = ext === 'xlsx' ? FileSpreadsheet : ['jpg', 'jpeg', 'png'].includes(ext) ? FileImage : FileText;
  return <Icon aria-hidden="true" className={cn('size-5 shrink-0 text-muted', className)} />;
}

/**
 * Поле завантаження: перетягніть файл або виберіть. Вид імпорту визначаємо самі (з поясненням) —
 * людина може змінити; для програм і планів просимо групи, бо в документі їх немає.
 */
export function UploadPanel({ onUploaded }: UploadPanelProps) {
  const inputId = useId();
  const inputRef = useRef<HTMLInputElement>(null);
  const upload = useUploadImport();
  const semesters = useSemesters();
  const [picked, setPicked] = useState<Picked | null>(null);
  const [dragging, setDragging] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const [semesterId, setSemesterId] = useState<string | null>(null);
  // importer привʼязує години за кодами груп (options.groupCodes), тож і зберігаємо коди
  const [groupCodes, setGroupCodes] = useState<string[]>([]);
  const [groupError, setGroupError] = useState<string | null>(null);
  const [planSemester, setPlanSemester] = useState<number | null>(null);
  const [planSemesterError, setPlanSemesterError] = useState<string | null>(null);
  const [versionName, setVersionName] = useState('');
  const [hoursPerWeek, setHoursPerWeek] = useState<number | null>(null);
  const semester = semesters.data?.find((s) => s.id === semesterId) ?? activeSemesterOf(semesters.data);

  const choose = (file: File | undefined) => {
    if (!file) return;
    setProblem(null);
    const issue = fileProblem(file);
    if (issue) {
      setPicked(null);
      setProblem(issue);
      return;
    }
    const detection = detectImportKind(file.name);
    if (!detection) {
      setProblem(`Не вдалося визначити, що в «${file.name}».`);
      return;
    }
    setPicked({ file, kind: detection.kind, reason: detection.reason });
  };

  const onDrop = (event: DragEvent<HTMLElement>) => {
    event.preventDefault();
    setDragging(false);
    if (event.dataTransfer.files.length > 1) setProblem('Перетягніть один файл: кожен документ імпортується окремо.');
    else choose(event.dataTransfer.files[0]);
  };

  const needsGroups = picked?.kind === 'DISCIPLINE_PROGRAM' || picked?.kind === 'CURRICULUM';
  const formatMismatch = picked ? !kindAccepts(picked.kind, picked.file.name) : false;

  /** Лише ключі, які приймає importer для цього виду (інший ключ → 400). */
  const optionsFor = (kind: ImportKind): ImportOptions => {
    const options: ImportOptions = {};
    if (kind === 'TIMETABLE_XLSX' && versionName.trim()) options.versionName = versionName.trim();
    if (kind === 'DISCIPLINE_PROGRAM' || kind === 'CURRICULUM') options.groupCodes = groupCodes;
    if (kind === 'CURRICULUM' && planSemester !== null) options.semesterNumber = planSemester;
    if (kind === 'DISCIPLINE_PROGRAM' && hoursPerWeek !== null) options.hoursPerWeek = hoursPerWeek;
    return options;
  };

  const submit = async () => {
    if (!picked) return;
    let invalid = false;
    if (needsGroups && groupCodes.length === 0) {
      setGroupError('Оберіть групи, яким читається дисципліна за цим документом.');
      invalid = true;
    }
    if (picked.kind === 'CURRICULUM' && planSemester === null) {
      setPlanSemesterError('Вкажіть, який семестр плану розпізнати.');
      invalid = true;
    }
    if (invalid) return;
    setProblem(null);
    try {
      const job = await upload.mutateAsync({
        file: picked.file,
        kind: picked.kind,
        semesterId: semester?.id ?? null,
        options: optionsFor(picked.kind),
      });
      notify.success(`«${picked.file.name}» завантажено — розбираємо`);
      setPicked(null);
      setGroupCodes([]);
      setPlanSemester(null);
      setVersionName('');
      setHoursPerWeek(null);
      onUploaded(job);
    } catch (error) {
      setProblem(applyServerErrors(error, () => undefined, []));
    }
  };

  return (
    <section aria-labelledby={`${inputId}-title`} className="flex flex-col gap-3">
      <h2 id={`${inputId}-title`} className="sr-only">
        Новий імпорт
      </h2>
      <input
        ref={inputRef}
        id={inputId}
        type="file"
        accept={ACCEPT_ATTRIBUTE}
        className="sr-only"
        tabIndex={-1}
        onChange={(event) => {
          choose(event.target.files?.[0]);
          event.target.value = '';
        }}
      />
      {picked ? (
        <div className="flex flex-col gap-4 rounded-panel border border-accent-subtle bg-surface p-4">
          <div className="flex flex-wrap items-center gap-3">
            <FileIcon name={picked.file.name} className="size-8 text-accent" />
            <div className="flex min-w-0 flex-1 flex-col">
              <p className="truncate text-body font-semibold text-primary" title={picked.file.name}>{picked.file.name}</p>
              <p className="text-caption text-muted">
                {formatBytes(picked.file.size)} · {picked.reason}
              </p>
            </div>
            <IconButton label="Прибрати файл" icon={<X aria-hidden="true" className="size-4" />} onClick={() => setPicked(null)} disabled={upload.isPending} />
          </div>
          <div className="grid gap-4 md:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
            <Field
              label="Що імпортуємо"
              error={formatMismatch ? `${IMPORT_KIND_LABEL[picked.kind]} у форматі .${extensionOf(picked.file.name)} не приймається` : undefined}
              hint={usesVision(picked.kind, picked.file.name) ? 'Розпізнає модель зору — це витрачає бюджет ШІ (кілька центів).' : undefined}
            >
              <Select<ImportKind> value={picked.kind} onValueChange={(kind) => setPicked({ ...picked, kind, reason: 'Вибрано вручну' })} options={IMPORT_KIND_OPTIONS} />
            </Field>
            <Field label="Семестр" hint="До якого семестру належать розклад чи години">
              <Select<string>
                value={semester?.id ?? null}
                onValueChange={setSemesterId}
                options={(semesters.data ?? []).map((s) => ({ value: s.id, label: s.name, description: s.active ? 'Активний' : undefined }))}
                placeholder="Оберіть семестр"
              />
            </Field>
          </div>
          {needsGroups ? (
            <Field label="Групи" required error={groupError} hint="Семестр у програмі часто застарілий — години привʼяжемо до цих груп">
              <GroupCodesPicker
                value={groupCodes}
                onChange={(codes) => {
                  setGroupCodes(codes);
                  if (codes.length > 0) setGroupError(null);
                }}
              />
            </Field>
          ) : null}
          {picked.kind === 'CURRICULUM' ? (
            <Field label="Семестр навчального плану" required error={planSemesterError} hint="Номер семестру в плані, від 1 до 8: напр., 7 — перший семестр 4 курсу">
              <NumberInput
                value={planSemester}
                onValueChange={(value) => {
                  setPlanSemester(value);
                  setPlanSemesterError(null);
                }}
                min={IMPORT_OPTION_LIMITS.semesterNumber.min}
                max={IMPORT_OPTION_LIMITS.semesterNumber.max}
                className="w-32"
              />
            </Field>
          ) : null}
          {picked.kind === 'DISCIPLINE_PROGRAM' ? (
            <Field label="Годин на тиждень" hint="Якщо програма цього не каже; інакше лишіть порожнім">
              <NumberInput
                value={hoursPerWeek}
                onValueChange={setHoursPerWeek}
                min={IMPORT_OPTION_LIMITS.hoursPerWeek.min}
                max={IMPORT_OPTION_LIMITS.hoursPerWeek.max}
                step={0.5}
                precision={1}
                className="w-32"
              />
            </Field>
          ) : null}
          {picked.kind === 'TIMETABLE_XLSX' ? (
            <Field label="Назва чернетки" hint="Необовʼязково: інакше — «Імпорт з «назва файлу»»">
              <Input
                value={versionName}
                onChange={(event) => setVersionName(event.target.value)}
                maxLength={IMPORT_OPTION_LIMITS.versionName}
                placeholder="Напр.: Розклад з 3 листопада"
              />
            </Field>
          ) : null}
          <FormAlert message={problem} />
          <div className="flex flex-wrap justify-end gap-2">
            <Button variant="secondary" onClick={() => inputRef.current?.click()} disabled={upload.isPending}>
              Інший файл
            </Button>
            <Button
              variant="primary"
              loading={upload.isPending}
              loadingLabel="Завантажуємо"
              disabled={formatMismatch || !semester}
              iconStart={<Upload aria-hidden="true" className="size-4" />}
              onClick={() => void submit()}
            >
              Завантажити й розібрати
            </Button>
          </div>
        </div>
      ) : (
        <>
          <button
            type="button"
            onClick={() => inputRef.current?.click()}
            onDragOver={(event) => {
              event.preventDefault();
              if (!dragging) setDragging(true);
            }}
            onDragLeave={() => setDragging(false)}
            onDrop={onDrop}
            aria-describedby={`${inputId}-formats`}
            className={cn(
              'group flex w-full flex-col items-center gap-2 rounded-panel border-2 border-dashed px-6 py-8 text-center',
              'transition-[background-color,border-color] duration-150 ease-out',
              'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus',
              dragging ? 'border-accent bg-selected' : 'border-strong bg-surface hover:border-accent hover:bg-hover',
            )}
          >
            <Upload aria-hidden="true" className={cn('size-6 text-muted transition-transform duration-150', dragging && 'text-accent motion-safe:-translate-y-0.5')} />
            <span className="text-body font-semibold text-primary">{dragging ? 'Відпустіть, щоб додати файл' : 'Перетягніть файл сюди або виберіть'}</span>
            <span id={`${inputId}-formats`} className="text-caption text-muted">
              Розклад XLSX · програми DOCX, DOC, PDF · навчальний план і фото розпоряджень JPG, PNG, PDF · до 20 МБ
            </span>
          </button>
          <FormAlert message={problem} />
        </>
      )}
    </section>
  );
}
