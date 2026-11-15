import { Link } from 'react-router';
import { useCurrentUser } from '@/auth/useCurrentUser';
import { EmailPreferences } from '@/components/notifications/EmailPreferences';
import { PageHeader } from '@/components/ui';

/**
 * Сповіщення кабінету: які листи про мої заміни приходять на корпоративну пошту. Адреса — з облікового
 * запису (лише для читання). Telegram налаштовується на «Мій розклад» і тут не змінюється.
 */
export default function MyNotificationsPage() {
  const { isAdmin } = useCurrentUser();
  return (
    <div className="flex flex-col gap-8">
      <PageHeader title="Сповіщення" size="headline" />
      <div className="max-w-2xl lg:rounded-panel lg:border lg:border-default lg:bg-surface lg:p-8">
        <EmailPreferences
          audiences={['TEACHER']}
          emptyNote="Листів про заміни немає: обліковий запис ще не повʼязаний з викладачем. Зверніться до диспетчера."
          footer={
            isAdmin ? (
              <>
                Листи диспетчера —{' '}
                <Link to="/admin/notifications" className="font-[650] text-accent underline-offset-2 hover:underline">
                  у панелі диспетчера
                </Link>
                .
              </>
            ) : null
          }
        />
      </div>
    </div>
  );
}
