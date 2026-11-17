import { Link } from 'react-router';
import { useMe } from '@/api/me';
import { useCurrentUser } from '@/auth/useCurrentUser';
import { EmailPreferences } from '@/components/notifications/EmailPreferences';
import { TelegramConnect } from '@/components/teacher/TelegramConnect';
import { ErrorState, PageHeader, Skeleton } from '@/components/ui';

/**
 * Сповіщення кабінету: листи про мої заміни на корпоративну пошту (адреса з облікового запису, лише для
 * читання) і мій Telegram — чий акаунт привʼязано, відвʼязати, підключити знову. На десктопі пошта
 * ліворуч, Telegram — вузькою колонкою праворуч, як «Попереду» на «Мої відсутності»; на телефоні
 * спершу короткий стан Telegram, потім перемикачі листів.
 */
export default function MyNotificationsPage() {
  const { isAdmin } = useCurrentUser();
  const me = useMe();
  const teacher = me.data?.teacher ?? null;
  return (
    <div className="flex flex-col gap-8">
      <PageHeader title="Сповіщення" size="headline" />
      <div className="grid items-start gap-x-12 gap-y-10 lg:grid-cols-[minmax(0,1fr)_20rem]">
        <aside aria-label="Telegram" className="lg:order-last">
          {me.isPending ? (
            <Skeleton className="h-24 w-full rounded-panel" />
          ) : me.isError ? (
            <ErrorState error={me.error} onRetry={() => void me.refetch()} size="sm" title="Не вдалося перевірити Telegram" className="py-0" />
          ) : teacher ? (
            <TelegramConnect teacherId={teacher.id} allowUnlink />
          ) : (
            // без викладача Telegram кабінету підключати нікому
            <section aria-labelledby="telegram-none-heading" className="flex flex-col gap-3">
              <h2 id="telegram-none-heading" className="text-title text-primary">
                Telegram
              </h2>
              <p className="text-ui text-secondary">Запити на заміни в Telegram отримує викладач. Ваш обліковий запис з викладачем не повʼязаний.</p>
            </section>
          )}
        </aside>
        <section aria-labelledby="email-heading" className="flex flex-col gap-6 lg:rounded-panel lg:border lg:border-default lg:bg-surface lg:p-8">
          <h2 id="email-heading" className="text-title text-primary">
            Пошта
          </h2>
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
        </section>
      </div>
    </div>
  );
}
