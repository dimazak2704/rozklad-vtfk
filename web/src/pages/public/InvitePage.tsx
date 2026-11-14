import { CircleCheck, LogIn } from 'lucide-react';
import { useState, type ReactNode } from 'react';
import { Link, useParams } from 'react-router';
import { isApiError } from '@/api/errors';
import { useInvite } from '@/api/public';
import { paths } from '@/app/routes';
import { InviteForm } from '@/components/public/invite/InviteForm';
import { ErrorState, LoadingRegion, PageHeader, Skeleton, buttonClass } from '@/components/ui';
import { useNow } from '@/hooks/useNow';
import { formatInstant } from '@/lib/date';

function Frame({ title, children }: { title: string; children: ReactNode }) {
  return (
    // як інші системні сторінки — від лівого краю вмісту, а не по центру
    <div className="flex w-full max-w-md flex-col gap-5 py-2 sm:py-8">
      <PageHeader size="headline" title={title} />
      {children}
    </div>
  );
}

function LoginLink({ primary = false }: { primary?: boolean }) {
  return (
    <Link to={paths.login('/me')} className={buttonClass({ variant: primary ? 'primary' : 'secondary', size: primary ? 'lg' : 'md', className: 'self-start' })}>
      <LogIn aria-hidden="true" className="size-4" />
      Увійти
    </Link>
  );
}

/** Реєстрація викладача за посиланням-запрошенням від диспетчера (ARCHITECTURE §6). */
export default function InvitePage() {
  const { token = '' } = useParams();
  const invite = useInvite(token);
  // строк запрошення — справжній час (сервер не переводить його разом із часом стенда)
  const { realNow: now } = useNow();
  const [created, setCreated] = useState<string | null>(null);
  const [gone, setGone] = useState<string | null>(null);

  if (created) {
    return (
      <Frame title="Обліковий запис створено">
        <p className="flex items-start gap-2.5 text-body text-primary" role="status">
          <CircleCheck aria-hidden="true" className="mt-1 size-5 shrink-0 text-add" />
          <span>
            Ваш логін — <span className="font-semibold">{created}</span>. Увійдіть із ним і паролем: відкриється кабінет з вашим
            розкладом і запитами на заміну.
          </span>
        </p>
        <LoginLink primary />
      </Frame>
    );
  }

  if (gone) {
    return (
      <Frame title="Запрошення вже недійсне">
        <p className="text-body text-secondary">{gone}</p>
        <LoginLink />
      </Frame>
    );
  }

  if (invite.isPending) {
    return (
      <Frame title="Реєстрація викладача">
        <LoadingRegion label="Перевіряємо запрошення…">
          <div className="flex flex-col gap-4">
            <Skeleton className="h-5 w-3/4" />
            <Skeleton className="h-9 w-full" />
            <Skeleton className="h-9 w-full" />
            <Skeleton className="h-9 w-full" />
          </div>
        </LoadingRegion>
      </Frame>
    );
  }

  if (invite.isError) {
    if (isApiError(invite.error) && invite.error.isNotFound) {
      return (
        <Frame title="Запрошення не знайдено">
          <p className="text-body text-secondary">
            Посилання неповне або застаріле. Скопіюйте його з листа чи повідомлення ще раз, а якщо не допоможе — попросіть
            диспетчера надіслати нове запрошення.
          </p>
          <LoginLink />
        </Frame>
      );
    }
    return (
      <Frame title="Реєстрація викладача">
        <ErrorState error={invite.error} align="start" onRetry={() => void invite.refetch()} retrying={invite.isFetching} />
      </Frame>
    );
  }

  const info = invite.data;
  if (!info.valid) {
    const expired = new Date(info.expiresAt).getTime() <= now.getTime();
    return (
      <Frame title={expired ? 'Термін запрошення минув' : 'Запрошення вже використано'}>
        <p className="text-body text-secondary">
          {expired
            ? `Запрошення для ${info.teacherName} діяло до ${formatInstant(info.expiresAt)}. Попросіть диспетчера надіслати нове.`
            : `За цим запрошенням обліковий запис для ${info.teacherName} уже створено. Увійдіть зі своїм логіном.`}
        </p>
        <LoginLink />
      </Frame>
    );
  }

  return (
    <Frame title="Реєстрація викладача">
      <p className="text-body text-secondary">
        Запрошення для <span className="font-[650] text-primary">{info.teacherName}</span> — придумайте логін і пароль.
        Диспетчер пароля не бачить.
        <span className="mt-1 block text-caption text-muted">
          Посилання діє до <span className="figures">{formatInstant(info.expiresAt)}</span>.
        </span>
      </p>
      <InviteForm token={token} onAccepted={setCreated} onGone={setGone} />
    </Frame>
  );
}
