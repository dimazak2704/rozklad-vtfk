import { CircleAlert, CircleCheck, Info, Mail } from 'lucide-react';
import type { ReactNode } from 'react';
import { useEmailPreferences, useSendTestEmail, useUpdateEmailPreferences } from '@/api/email';
import { isApiError } from '@/api/errors';
import type { EmailAudience, EmailKindSettingDto, EmailPreferencesDto } from '@/api/types';
import { Button, ErrorState, LoadingRegion, notify, Skeleton, Switch } from '@/components/ui';
import { addressNote, emailKindText } from './labels';

const GROUP_TITLE: Record<EmailAudience, string> = {
  TEACHER: 'Про мої заміни',
  ADMIN: 'Для диспетчера',
};

export interface EmailPreferencesProps {
  /** Які групи листів показати і в якому порядку (кабінет — лише свої заміни). */
  audiences: readonly EmailAudience[];
  /** Що сказати, коли жодного виду листів для цих груп немає (наприклад, обліковий запис без викладача). */
  emptyNote?: ReactNode;
  /** Рядок під списком: де налаштувати інші листи. */
  footer?: ReactNode;
}

/**
 * Мої листи на пошту: адреса (лише для читання) і що робити, якщо листи на неї не підуть; загальний
 * перемикач; по перемикачу на кожен вид листів з одним реченням, коли він приходить. Перемикачі діють
 * одразу — кнопки «Зберегти» немає. Telegram ці налаштування не змінюють.
 */
export function EmailPreferences({ audiences, emptyNote, footer }: EmailPreferencesProps) {
  const prefs = useEmailPreferences();

  if (prefs.isPending) {
    return (
      <LoadingRegion label="Завантажуємо налаштування листів…" className="flex flex-col gap-4">
        <Skeleton className="h-5 w-64 max-w-full" />
        <Skeleton className="h-4 w-80 max-w-full" />
        <Skeleton className="mt-4 h-9 w-full" />
        <Skeleton className="h-9 w-full" />
        <Skeleton className="h-9 w-full" />
      </LoadingRegion>
    );
  }
  if (prefs.isError) {
    return (
      <ErrorState
        error={prefs.error}
        title="Налаштування листів не завантажено"
        onRetry={() => void prefs.refetch()}
        retrying={prefs.isFetching}
      />
    );
  }
  return <EmailPreferencesForm prefs={prefs.data} audiences={audiences} emptyNote={emptyNote} footer={footer} />;
}

function EmailPreferencesForm({ prefs, audiences, emptyNote, footer }: EmailPreferencesProps & { prefs: EmailPreferencesDto }) {
  const update = useUpdateEmailPreferences();
  const channelOff = !prefs.channel.enabled;
  const groups = audiences
    .map((audience) => ({ audience, kinds: prefs.kinds.filter((k) => k.audience === audience) }))
    .filter((group) => group.kinds.length > 0);

  const toggleAll = (enabled: boolean) => update.mutate({ enabled });
  const toggleKind = (setting: EmailKindSettingDto, enabled: boolean) => update.mutate({ kind: setting.kind, enabled });

  return (
    <div className="flex flex-col gap-6">
      <AddressBlock prefs={prefs} />

      {channelOff ? (
        <p role="status" className="flex items-start gap-2 rounded-control bg-sunken px-3 py-2.5 text-ui text-secondary">
          <Info aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-muted" />
          <span>
            Сервер поки не надсилає листи{prefs.channel.reason ? `: ${prefs.channel.reason}` : ': пошту на ньому не налаштовано'}.
            Перемикачі запрацюють, щойно пошту підключать.
          </span>
        </p>
      ) : null}

      {groups.length === 0 ? (
        <p className="text-ui text-secondary">{emptyNote ?? 'Листів, які можна налаштувати, для вашого облікового запису немає.'}</p>
      ) : (
        <>
          <div className="border-y border-separator py-2">
            <Switch
              labelPosition="start"
              label={<span className="font-[650]">Надсилати листи на пошту</span>}
              description="Вимкніть, щоб система не писала вам на пошту. Листи безпеки приходять завжди."
              checked={prefs.enabled}
              disabled={channelOff}
              onCheckedChange={toggleAll}
            />
          </div>

          {groups.map((group) => (
            <section key={group.audience} aria-labelledby={`email-group-${group.audience}`} className="flex flex-col gap-1">
              <h3 id={`email-group-${group.audience}`} className="text-heading font-bold text-primary">
                {GROUP_TITLE[group.audience]}
              </h3>
              <ul className="flex flex-col divide-y divide-[var(--border-color-separator)]">
                {group.kinds.map((setting) => {
                  const text = emailKindText(setting.kind, setting);
                  // лист безпеки не вимикається: замість сірого перемикача — слово «Завжди»
                  if (!setting.switchable) {
                    return (
                      <li key={setting.kind} className="flex items-start justify-between gap-3 py-3 sm:py-2.5">
                        <div className="flex min-w-0 flex-col gap-0.5">
                          <span className="text-ui text-primary">{text.label}</span>
                          <p className="text-caption text-muted">{text.description}</p>
                        </div>
                        <span className="shrink-0 text-caption font-[650] text-secondary">Завжди</span>
                      </li>
                    );
                  }
                  return (
                    <li key={setting.kind}>
                      <Switch
                        labelPosition="start"
                        label={text.label}
                        description={text.description}
                        checked={prefs.enabled && setting.enabled}
                        disabled={channelOff || !prefs.enabled}
                        onCheckedChange={(enabled) => toggleKind(setting, enabled)}
                        className="py-3 sm:py-2.5"
                      />
                    </li>
                  );
                })}
              </ul>
            </section>
          ))}
          {!prefs.enabled && !channelOff ? (
            <p className="-mt-3 text-caption text-muted">Листи вимкнено — вибір за видами збережено і повернеться, коли ви їх увімкнете.</p>
          ) : null}
        </>
      )}

      <p className="text-caption text-muted">
        Telegram працює як і раніше: ці перемикачі стосуються лише пошти.
        {footer ? <> {footer}</> : null}
      </p>
    </div>
  );
}

/** Адреса — лише для читання: її бере сервер з облікового запису, змінити може навчальна частина. */
function AddressBlock({ prefs }: { prefs: EmailPreferencesDto }) {
  const note = addressNote(prefs);
  const test = useSendTestEmail();
  const canTest = note.ok && prefs.channel.enabled && Boolean(prefs.address);

  const sendTest = () =>
    test.mutate(undefined, {
      onSuccess: () =>
        notify.success('Тестовий лист у черзі', {
          description: `Він прийде на ${prefs.address ?? 'вашу адресу'} протягом кількох хвилин. Немає — перевірте папку «Спам».`,
        }),
      onError: (error) =>
        isApiError(error) && error.status === 429
          ? notify.error('Тестових листів забагато: не більше трьох на годину. Спробуйте пізніше.')
          : notify.error(error, 'Не вдалося надіслати тестовий лист'),
    });

  return (
    <div className="flex flex-col gap-2">
      <div className="flex min-w-0 items-center gap-2">
        <Mail aria-hidden="true" className="size-5 shrink-0 text-muted" />
        {prefs.address ? (
          <span className="min-w-0 text-body font-[650] break-all text-primary" aria-label={`Адреса для листів: ${prefs.address}`}>
            {prefs.address}
          </span>
        ) : (
          <span className="text-body font-[650] text-primary">Адреси немає</span>
        )}
      </div>
      <p className="flex items-start gap-2 text-ui text-secondary">
        {note.ok ? (
          <CircleCheck aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-add" />
        ) : (
          <CircleAlert aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-change" />
        )}
        <span>{note.text}</span>
      </p>
      {prefs.address ? <p className="text-caption text-muted">Змінити адресу може лише навчальна частина.</p> : null}
      {canTest ? (
        <div className="pt-1">
          <Button
            variant="secondary"
            iconStart={<Mail aria-hidden="true" className="size-4" />}
            loading={test.isPending}
            loadingLabel="Надсилаємо тестовий лист"
            onClick={sendTest}
          >
            Надіслати тестовий лист
          </Button>
        </div>
      ) : null}
    </div>
  );
}
