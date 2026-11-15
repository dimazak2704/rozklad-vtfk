import { Check, Copy } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import { usePasswordLink, type AccessDto } from '@/api/access';
import { Button, Dialog, DialogContent, ErrorState, Input, Skeleton } from '@/components/ui';
import { formatInstant } from '@/lib/date';
import { loginName } from './labels';

interface PasswordLinkDialogProps {
  access: AccessDto;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

/**
 * «Посилання для пароля»: створює одноразове посилання й показує його один раз (на сервері лише хеш).
 * Попереднє посилання цієї людини перестає діяти. Людина за посиланням ставить пароль і входить з логіном.
 */
export function PasswordLinkDialog({ access, open, onOpenChange }: PasswordLinkDialogProps) {
  const link = usePasswordLink();
  const { mutate } = link;
  const started = useRef(false);
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    if (open && !started.current) {
      started.current = true;
      mutate(access.id);
    }
  }, [open, access.id, mutate]);

  const copy = async () => {
    if (!link.data) return;
    try {
      await navigator.clipboard.writeText(link.data.url);
      setCopied(true);
    } catch {
      setCopied(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent
        title="Посилання для пароля"
        size="md"
        footer={
          <Button variant="secondary" onClick={() => onOpenChange(false)}>
            Готово
          </Button>
        }
      >
        {link.isError ? (
          <ErrorState error={link.error} align="start" onRetry={() => link.mutate(access.id)} retrying={link.isPending} />
        ) : link.data ? (
          <div className="flex flex-col gap-3">
            <p className="text-body text-secondary">
              Надішліть посилання людині: за ним <span className="font-[650] text-primary">{access.displayName}</span> задасть пароль
              {access.username ? (
                <>
                  {' '}
                  для логіна <span className="font-[650] text-primary">{loginName(access)}</span>
                </>
              ) : (
                ' і вибере логін'
              )}
              . Ми показуємо його лише зараз.
            </p>
            {/* кнопка — поруч із полем, а не всередині: довге посилання обрізається трикрапкою й ніколи не лягає під неї */}
            <div className="flex min-w-0 items-center gap-2">
              <Input
                aria-label="Посилання для пароля"
                readOnly
                value={link.data.url}
                onFocus={(event) => event.currentTarget.select()}
                className="min-w-0 flex-1 text-ellipsis font-mono text-body sm:text-ui"
              />
              <Button
                variant="secondary"
                onClick={() => void copy()}
                className="shrink-0"
                iconStart={copied ? <Check aria-hidden="true" className="size-4 text-add" /> : <Copy aria-hidden="true" className="size-4" />}
              >
                {copied ? 'Скопійовано' : 'Копіювати'}
              </Button>
            </div>
            <p className="text-caption text-muted">
              Діє до <span className="figures">{formatInstant(link.data.expiresAt)}</span>. Попереднє посилання вже не працює.
            </p>
          </div>
        ) : (
          <div aria-busy="true" className="flex flex-col gap-3">
            <Skeleton className="h-5 w-3/4" />
            <Skeleton className="h-11 w-full" />
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}
