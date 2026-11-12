import { zodResolver } from '@hookform/resolvers/zod';
import { Check, CircleCheck, CircleExclamationPoint, Eye, EyeOff, LogIn } from 'lucide-react';
import { useState, type ReactNode } from 'react';
import { useForm, useWatch } from 'react-hook-form';
import { Link, useParams } from 'react-router';
import { isApiError } from '@/api/errors';
import { useAcceptInvite, useInvite } from '@/api/public';
import { paths } from '@/app/routes';
import { inviteSchema, PASSWORD_RULES, type InviteFormValues } from '@/components/public/invite/invite-schema';
import { Button, ErrorState, Field, IconButton, Input, LoadingRegion, PageHeader, Skeleton, buttonClass } from '@/components/ui';
import { cn } from '@/lib/cn';
import { formatInstant } from '@/lib/date';

const FIELDS: ReadonlyArray<keyof InviteFormValues> = ['username', 'password', 'confirm'];

function Frame({ children }: { children: ReactNode }) {
  return (
    <div className="mx-auto flex w-full max-w-md flex-col gap-5 py-2 sm:py-8">
      <PageHeader size="headline" title="Новий пароль" />
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

/**
 * Пароль за одноразовим посиланням з «Доступів» (DESIGN §4.6): наявний логін показано без змін, а людина з
 * доступом лише за e-mail вибирає собі логін тут. Посилання діє один раз; новіше скасовує старе.
 */
export default function PasswordLinkPage() {
  const { token = '' } = useParams();
  const link = useInvite(token);
  const [done, setDone] = useState<string | null>(null);
  const [gone, setGone] = useState<string | null>(null);

  if (done) {
    return (
      <Frame>
        <p className="flex items-start gap-2.5 text-body text-primary" role="status">
          <CircleCheck aria-hidden="true" className="mt-1 size-5 shrink-0 text-add" />
          <span>
            Пароль збережено. Ваш логін — <span className="font-semibold">{done}</span>.
          </span>
        </p>
        <LoginLink primary />
      </Frame>
    );
  }

  if (gone) {
    return (
      <Frame>
        <p className="text-body text-secondary">{gone}</p>
        <LoginLink />
      </Frame>
    );
  }

  if (link.isPending) {
    return (
      <Frame>
        <LoadingRegion label="Перевіряємо посилання…">
          <div className="flex flex-col gap-4">
            <Skeleton className="h-5 w-3/4" />
            <Skeleton className="h-11 w-full" />
            <Skeleton className="h-11 w-full" />
          </div>
        </LoadingRegion>
      </Frame>
    );
  }

  if (link.isError) {
    return (
      <Frame>
        {isApiError(link.error) && link.error.isNotFound ? (
          <p className="text-body text-secondary">Посилання неповне або застаріле. Попросіть у навчальній частині нове.</p>
        ) : (
          <ErrorState error={link.error} align="start" onRetry={() => void link.refetch()} retrying={link.isFetching} />
        )}
      </Frame>
    );
  }

  const info = link.data;
  if (!info.valid) {
    const text =
      info.status === 'REVOKED'
        ? 'Доступ відкликано або посилання замінили новим. Зверніться до навчальної частини.'
        : info.status === 'USED'
          ? 'Цим посиланням уже скористалися. Увійдіть зі своїм логіном і паролем.'
          : 'Термін дії посилання минув. Попросіть у навчальній частині нове.';
    return (
      <Frame>
        <p className="text-body text-secondary">{text}</p>
        <LoginLink />
      </Frame>
    );
  }

  return (
    <Frame>
      <p className="text-body text-secondary">
        <span className="font-[650] text-primary">{info.teacherName}</span>, задайте пароль для входу до розкладу.
        <span className="mt-1 block text-caption text-muted">
          Посилання діє до <span className="figures">{formatInstant(info.expiresAt)}</span>.
        </span>
      </p>
      <PasswordForm token={token} username={info.username ?? null} onDone={setDone} onGone={setGone} />
    </Frame>
  );
}

interface PasswordFormProps {
  token: string;
  /** Наявний логін облікового запису; null — людина вибирає його тут. */
  username: string | null;
  onDone: (username: string) => void;
  onGone: (message: string) => void;
}

function PasswordForm({ token, username, onDone, onGone }: PasswordFormProps) {
  const accept = useAcceptInvite(token);
  const [showPassword, setShowPassword] = useState(false);
  const form = useForm<InviteFormValues>({
    resolver: zodResolver(inviteSchema),
    defaultValues: { username: username ?? '', password: '', confirm: '' },
    mode: 'onTouched',
  });
  const { errors, isSubmitting } = form.formState;
  const password = useWatch({ control: form.control, name: 'password' });

  const submit = form.handleSubmit(async (values) => {
    try {
      const result = await accept.mutateAsync({ username: values.username, password: values.password });
      onDone(result.username);
    } catch (error) {
      if (!isApiError(error)) {
        form.setError('root.server', { message: 'Щось пішло не так. Спробуйте ще раз.' });
        return;
      }
      if (error.status === 410) {
        onGone(error.message);
        return;
      }
      const fieldErrors = error.fieldErrors();
      let mapped = false;
      for (const field of FIELDS) {
        if (fieldErrors[field]) {
          form.setError(field, { message: fieldErrors[field] }, { shouldFocus: !mapped });
          mapped = true;
        }
      }
      if (error.status === 409 && !mapped && !username) {
        form.setError('username', { message: error.message }, { shouldFocus: true });
        mapped = true;
      }
      if (!mapped) form.setError('root.server', { message: error.message });
    }
  });

  return (
    <form noValidate onSubmit={(event) => void submit(event)} className="flex flex-col gap-4">
      {username ? (
        <Field label="Логін">
          <Input value={username} readOnly autoComplete="username" />
        </Field>
      ) : (
        <Field label="Логін" required error={errors.username?.message} hint="Латиницею, наприклад kolesnyk.a">
          <Input {...form.register('username')} autoComplete="username" autoCapitalize="none" spellCheck={false} />
        </Field>
      )}
      <Field label="Пароль" required error={errors.password?.message}>
        <Input
          {...form.register('password')}
          type={showPassword ? 'text' : 'password'}
          autoComplete="new-password"
          endSlot={
            <IconButton
              label={showPassword ? 'Сховати пароль' : 'Показати пароль'}
              size="sm"
              pressed={showPassword}
              icon={showPassword ? <EyeOff aria-hidden="true" className="size-4" /> : <Eye aria-hidden="true" className="size-4" />}
              onClick={() => setShowPassword((value) => !value)}
            />
          }
        />
      </Field>
      <ul aria-label="Вимоги до пароля" className="-mt-2 flex flex-col gap-1">
        {PASSWORD_RULES.map((rule) => {
          const ok = rule.test(password);
          return (
            <li key={rule.id} className={cn('flex items-center gap-2 text-caption', ok ? 'text-add' : 'text-muted')}>
              <Check aria-hidden="true" className={cn('size-3.5', !ok && 'opacity-40')} />
              {rule.label}
              <span className="sr-only">{ok ? ' — виконано' : ' — ще ні'}</span>
            </li>
          );
        })}
      </ul>
      <Field label="Повторіть пароль" required error={errors.confirm?.message}>
        <Input {...form.register('confirm')} type={showPassword ? 'text' : 'password'} autoComplete="new-password" />
      </Field>

      {errors.root?.server?.message ? (
        <p role="alert" className="flex items-start gap-2 rounded-control border border-cancel bg-danger-subtle px-3 py-2 text-ui text-danger">
          <CircleExclamationPoint aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
          {errors.root.server.message}
        </p>
      ) : null}

      <Button type="submit" variant="primary" size="lg" loading={isSubmitting} loadingLabel="Зберігаємо пароль" className="mt-1">
        Зберегти пароль
      </Button>
    </form>
  );
}
