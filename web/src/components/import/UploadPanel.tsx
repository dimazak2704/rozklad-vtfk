import { FileImage, FileSpreadsheet, FileText, Upload, X } from 'lucide-react';
import { useId, useRef, useState, type DragEvent } from 'react';
import { activeSemesterOf, useSemesters } from '@/api/calendar';
import { useCatalogOptions } from '@/api/catalog';
import { useUploadImport, type ImportJob } from '@/api/import';
import type { ImportKind } from '@/api/types';
import { applyServerErrors } from '@/components/catalog/forms';
import { FormAlert } from '@/components/catalog/FormParts';
import { Button, Combobox, Field, IconButton, Plate, Select, notify } from '@/components/ui';
import { cn } from '@/lib/cn';
import { ACCEPT_ATTRIBUTE, detectImportKind, extensionOf, fileProblem, formatBytes, kindAccepts, usesVision } from './detect';
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
  const groups = useCatalogOptions('groups', { active: true });
  const [picked, setPicked] = useState<Picked | null>(null);
  const [dragging, setDragging] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const [semesterId, setSemesterId] = useState<string | null>(null);
  const [groupIds, setGroupIds] = useState<string[]>([]);
  const [groupError, setGroupError] = useState<string | null>(null);
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

  const submit = async () => {
    if (!picked) return;
    if (needsGroups && groupIds.length === 0) {
      setGroupError('Оберіть групи, яким читається дисципліна за цим документом.');
      return;
    }
    setProblem(null);
    try {
      const job = await upload.mutateAsync({
        file: picked.file,
        kind: picked.kind,
        semesterId: semester?.id ?? null,
        options: needsGroups ? { groupIds } : null,
      });
      notify.success(`«${picked.file.name}» завантажено — розбираємо`);
      setPicked(null);
      setGroupIds([]);
      onUploaded(job);
    } catch (error) {
      setProblem(applyServerErrors(error, () => undefined, []));
    }
  };

  const groupOptions = (groups.items ?? []).filter((g) => !groupIds.includes(g.id)).map((g) => ({ value: g.id, label: g.code, description: `${g.course} курс` }));

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
              <div className="flex flex-col gap-2">
                <Combobox
                  value={null}
                  onValueChange={(id) => {
                    if (id) {
                      setGroupIds([...groupIds, id]);
                      setGroupError(null);
                    }
                  }}
                  options={groupOptions}
                  placeholder="Додати групу…"
                  searchPlaceholder="Код групи"
                  loading={groups.isPending}
                />
                {groupIds.length > 0 ? (
                  <ul className="flex flex-wrap gap-1.5" aria-label="Вибрані групи">
                    {groupIds.map((id) => {
                      const group = groups.items?.find((g) => g.id === id);
                      return (
                        <li key={id} className="inline-flex items-center gap-0.5">
                          <Plate size="md">{group?.code ?? '…'}</Plate>
                          <IconButton
                            label={`Прибрати групу ${group?.code ?? ''}`}
                            icon={<X aria-hidden="true" className="size-3.5" />}
                            size="sm"
                            className="size-7"
                            onClick={() => setGroupIds(groupIds.filter((g) => g !== id))}
                          />
                        </li>
                      );
                    })}
                  </ul>
                ) : null}
              </div>
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
