import { Link } from 'react-router';
import { useCurrentUser } from '@/auth/useCurrentUser';
import { Button } from '@/components/ui/Button';
import { buttonClass } from '@/components/ui/styles';

/** 403: увійшли, але ролі бракує (наприклад, викладач відкрив адмінку). */
export function ForbiddenPage() {
  const { user, isTeacher, signOut } = useCurrentUser();
  return (
    <section className="mx-auto flex max-w-xl flex-col gap-4 px-4 py-16">
      <h1 className="text-title font-semibold text-primary">Цей розділ для диспетчерів</h1>
      <p className="text-body text-secondary">
        Ви увійшли як <span className="font-semibold text-primary">{user?.name ?? 'користувач'}</span>, і у вашого облікового
        запису немає доступу сюди. Якщо доступ потрібен для роботи, зверніться до заступника директора з навчальної роботи.
      </p>
      <div className="flex flex-wrap gap-2">
        <Link to={isTeacher ? '/me' : '/'} className={buttonClass({ variant: 'primary' })}>
          {isTeacher ? 'До мого розкладу' : 'На головну'}
        </Link>
        <Button variant="ghost" onClick={() => void signOut()}>
          Увійти іншим обліковим записом
        </Button>
      </div>
    </section>
  );
}
