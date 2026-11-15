import type { EmailStatusDto } from '@/api/types';
import { Skeleton, StatusDot } from '@/components/ui';

/**
 * Стан поштового сервера одним рядком під заголовком: чи йдуть листи, куди (сервер і порт), від кого.
 * Логіна й пароля SMTP сервер не віддає взагалі.
 */
export function EmailStatusLine({ status, loading, failed }: { status: EmailStatusDto | undefined; loading: boolean; failed: boolean }) {
  if (loading) return <Skeleton className="h-5 w-72 max-w-full" />;
  if (failed || !status) return <span className="text-danger">Стан пошти не завантажено.</span>;

  if (status.mode === 'OFF' || !status.enabled) {
    return (
      <span className="flex flex-wrap items-center gap-x-3 gap-y-1">
        <StatusDot tone="cancel" label="Листи не надсилаються" showLabel />
        <span>{status.problem ?? 'Пошту на сервері не налаштовано (MAIL_HOST порожній).'}</span>
      </span>
    );
  }

  const server = status.host ? `${status.host}${status.port ? `:${status.port}` : ''}` : null;
  return (
    <span className="flex flex-wrap items-center gap-x-3 gap-y-1">
      {status.mode === 'DEV_CATCHER' ? (
        <StatusDot tone="change" label="Тестова пошта стенду" showLabel />
      ) : (
        <StatusDot tone="live" label="Листи надсилаються" showLabel />
      )}
      {server ? (
        <span className="figures">
          {server}
          {status.tls && status.tls !== 'NONE' ? ` · ${status.tls}` : ''}
        </span>
      ) : null}
      {status.from ? <span className="min-w-0 break-all">Від: {status.from}</span> : null}
      {status.mode === 'DEV_CATCHER' ? <span>Листи лишаються в Mailpit і справжнім адресатам не йдуть.</span> : null}
      {status.problem ? <span className="text-danger">{status.problem}</span> : null}
    </span>
  );
}
