import { Check, Copy, QrCode } from 'lucide-react';
import { QRCodeSVG } from 'qrcode.react';
import { useMemo, useState } from 'react';
import { useGroupDirectory, useTeacherDirectory } from '@/api/admin-daily';
import { isApiError } from '@/api/errors';
import { useCreateBindingCode } from '@/api/telegram';
import type { BindingCodeDto, BindingType, Uuid } from '@/api/types';
import { Button, Combobox, Dialog, DialogContent, Field, IconButton, notify, Plate, RadioGroup } from '@/components/ui';
import { useNow } from '@/hooks/useNow';
import { formatInstant } from '@/lib/date';
import { durationLabel } from '@/lib/labels';

type CodeType = Exclude<BindingType, 'SUBSCRIBER'>;

const TYPE_OPTIONS: ReadonlyArray<{ value: CodeType; label: string; description: string }> = [
  { value: 'GROUP_CHAT', label: 'Чат групи', description: 'Група отримуватиме свої рядки розпорядження й зображення документа.' },
  { value: 'TEACHER', label: 'Викладач', description: 'Особисті запити на заміни з кнопками «Підтверджую» / «Не можу».' },
  { value: 'ADMIN_CHAT', label: 'Чат диспетчерів', description: 'Нові відсутності, відмови, прострочені підтвердження, публікації.' },
];

function CopyButton({ value, label }: { value: string; label: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <IconButton
      label={copied ? 'Скопійовано' : label}
      icon={copied ? <Check aria-hidden="true" className="size-4 text-add" /> : <Copy aria-hidden="true" className="size-4" />}
      size="sm"
      onClick={() => {
        void navigator.clipboard.writeText(value).then(
          () => {
            setCopied(true);
            window.setTimeout(() => setCopied(false), 1_600);
          },
          () => notify.error('Не вдалося скопіювати — виділіть текст і скопіюйте вручну'),
        );
      }}
    />
  );
}

function Instructions({ type, code }: { type: CodeType; code: string }) {
  if (type === 'GROUP_CHAT') {
    return (
      <ol className="flex list-decimal flex-col gap-1 ps-5 text-ui text-secondary">
        <li>Куратор відкриває посилання чи QR і обирає чат групи — бот додається туди сам.</li>
        <li>
          Або вручну: додати бота в чат і надіслати <code className="rounded-plate bg-sunken px-1 font-mono text-primary">/bind {code}</code>.
        </li>
        <li>Чат зʼявиться в таблиці привʼязок, щойно бот отримає код.</li>
      </ol>
    );
  }
  return (
    <ol className="flex list-decimal flex-col gap-1 ps-5 text-ui text-secondary">
      <li>{type === 'TEACHER' ? 'Викладач' : 'Диспетчер'} відкриває посилання чи сканує QR телефоном.</li>
      <li>У Telegram натискає «Почати» — бот привʼяже чат і привітається.</li>
    </ol>
  );
}

function Result({ type, result, targetLabel }: { type: CodeType; result: BindingCodeDto; targetLabel: string | null }) {
  const { now } = useNow();
  const left = Math.max(0, Math.round((Date.parse(result.expiresAt) - now.getTime()) / 60_000));
  return (
    <div className="grid gap-5 sm:grid-cols-[minmax(0,1fr)_auto]">
      <div className="flex min-w-0 flex-col gap-3">
        <div className="flex flex-col gap-1">
          <span className="text-caption text-muted">Код привʼязки{targetLabel ? ` · ${targetLabel}` : ''}</span>
          <span className="flex items-center gap-2">
            <Plate size="lg" className="tracking-[0.12em]">
              {result.code}
            </Plate>
            <CopyButton value={result.code} label="Копіювати код" />
          </span>
          <span className="text-caption text-secondary figures">
            {left > 0 ? `Діє ще ${durationLabel(left)} — до ${formatInstant(result.expiresAt)}` : 'Строк дії минув — створіть новий код'}
          </span>
        </div>
        <div className="flex min-w-0 flex-col gap-1">
          <span className="text-caption text-muted">Посилання</span>
          <span className="flex min-w-0 items-center gap-1 rounded-control border border-default bg-sunken ps-2.5">
            <a href={result.deepLink} target="_blank" rel="noopener noreferrer" className="min-w-0 flex-1 truncate font-mono text-caption text-accent underline-offset-4 hover:underline">
              {result.deepLink}
            </a>
            <CopyButton value={result.deepLink} label="Копіювати посилання" />
          </span>
        </div>
        <Instructions type={type} code={result.code} />
      </div>
      <figure className="flex flex-col items-center gap-1.5 self-start">
        <div data-theme="light" className="rounded-panel border border-default bg-surface p-3">
          <QRCodeSVG value={result.deepLink} size={148} level="M" marginSize={0} title={`QR-код: ${result.deepLink}`} />
        </div>
        <figcaption className="flex items-center gap-1 text-caption text-muted">
          <QrCode aria-hidden="true" className="size-3.5" />
          Скануйте камерою телефона
        </figcaption>
      </figure>
    </div>
  );
}

interface BindingCodeDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

/** Код привʼязки чату: для групи, викладача чи диспетчерів — з посиланням, QR і строком дії. */
export function BindingCodeDialog({ open, onOpenChange }: BindingCodeDialogProps) {
  const [type, setType] = useState<CodeType>('GROUP_CHAT');
  const [targetId, setTargetId] = useState<Uuid | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<BindingCodeDto | null>(null);
  const groups = useGroupDirectory();
  const teachers = useTeacherDirectory();
  const create = useCreateBindingCode();

  const groupOptions = useMemo(
    () => (groups.data ?? []).map((g) => ({ value: g.id, label: g.code, description: `${g.course} курс, ${g.department}` })),
    [groups.data],
  );
  const teacherOptions = useMemo(
    () => (teachers.data ?? []).map((t) => ({ value: t.id, label: t.shortName, keywords: [t.displayName] })),
    [teachers.data],
  );
  const needsTarget = type !== 'ADMIN_CHAT';
  const targetLabel =
    type === 'GROUP_CHAT'
      ? (groupOptions.find((o) => o.value === targetId)?.label ?? null)
      : type === 'TEACHER'
        ? (teacherOptions.find((o) => o.value === targetId)?.label ?? null)
        : null;

  const submit = async () => {
    if (needsTarget && !targetId) {
      setError(type === 'GROUP_CHAT' ? 'Оберіть групу, чий чат привʼязуємо' : 'Оберіть викладача');
      return;
    }
    setError(null);
    try {
      const code = await create.mutateAsync({
        type,
        groupId: type === 'GROUP_CHAT' ? targetId : null,
        teacherId: type === 'TEACHER' ? targetId : null,
      });
      setResult(code);
    } catch (cause) {
      setError(isApiError(cause) ? cause.message : 'Не вдалося створити код. Спробуйте ще раз.');
    }
  };

  const reset = () => {
    setResult(null);
    setTargetId(null);
    setError(null);
  };

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (!next) reset();
        onOpenChange(next);
      }}
    >
      <DialogContent
        title={result ? 'Код готовий' : 'Новий код привʼязки'}
        description={result ? 'Надішліть посилання або покажіть QR — код одноразовий.' : 'Що привʼязуємо до бота @scheduler_vtfk_bot?'}
        size="lg"
        footer={
          result ? (
            <>
              <Button variant="secondary" onClick={reset}>
                Створити ще один
              </Button>
              <Button variant="primary" onClick={() => onOpenChange(false)}>
                Готово
              </Button>
            </>
          ) : (
            <>
              <Button variant="secondary" onClick={() => onOpenChange(false)} disabled={create.isPending}>
                Скасувати
              </Button>
              <Button variant="primary" loading={create.isPending} onClick={() => void submit()}>
                Створити код
              </Button>
            </>
          )
        }
      >
        {result ? (
          <Result type={type} result={result} targetLabel={targetLabel} />
        ) : (
          <div className="flex flex-col gap-4">
            <RadioGroup<CodeType>
              legend="Тип чату"
              value={type}
              onValueChange={(value) => {
                setType(value);
                setTargetId(null);
                setError(null);
              }}
              options={TYPE_OPTIONS}
            />
            {type === 'GROUP_CHAT' ? (
              <Field label="Група" required error={error}>
                <Combobox value={targetId} onValueChange={setTargetId} options={groupOptions} loading={groups.isPending} placeholder="Напр.: 4КІ1" searchPlaceholder="Код групи, напр. «4ki1»" />
              </Field>
            ) : type === 'TEACHER' ? (
              <Field label="Викладач" required error={error}>
                <Combobox value={targetId} onValueChange={setTargetId} options={teacherOptions} loading={teachers.isPending} placeholder="Почніть вводити прізвище" searchPlaceholder="Прізвище, напр. «кренц»" />
              </Field>
            ) : error ? (
              <p role="alert" className="text-ui font-medium text-danger">
                {error}
              </p>
            ) : null}
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}
