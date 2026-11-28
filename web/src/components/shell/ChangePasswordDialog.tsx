import { zodResolver } from '@hookform/resolvers/zod';
import { useQueryClient } from '@tanstack/react-query';
import { CircleExclamationPoint, Eye, EyeOff } from 'lucide-react';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import {
  accountKeys,
  authProblemCode,
  startGoogleReauth,
  useChangeOwnPassword,
  useOwnPasswordInfo,
  useSetInitialPassword,
  type OwnPasswordInfoDto,
} from '@/api/account';
import { isApiError } from '@/api/errors';
import { Button, Dialog, DialogClose, DialogContent, ErrorState, Field, IconButton, Input, LoadingRegion, Skeleton, notify } from '@/components/ui';

const FORM_ID = 'own-password-form';

/** Чому вікно просить підтвердити вхід через Google: щойно повернулися без підтвердження чи воно застаріло. */
export type ReauthNotice = 'returned' | 'expired' | null;

interface ChangePasswordDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** Логін для менеджера паролів (приховане поле `autocomplete="username"`). */
  username?: string;
  /** Вікно відкрилося після повернення з Google (`?password=set`). */
  returnedFromGoogle?: boolean;
}

/**
 * «Змінити пароль» з меню користувача: поточний пароль, новий і повтор. Правила нового пароля — `PasswordPolicy`
 * сервісу входу (довжину він віддає разом зі станом); решту (поширений, логін, той самий) каже сервер. Після зміни
 * інші пристрої виходять: їхні сесії входу сервіс завершує, ця лишається.
 *
 * Без пароля (вхід лише через Google) це «Встановити пароль»: спершу людина ще раз входить через Google у цьому
 * браузері (сервіс входу вимагає свіжого підтвердження, одного токена мало), потім задає пароль без поточного.
 * Для спільного демо-доступу форми немає — лише пояснення.
 */
export function ChangePasswordDialog({ open, onOpenChange, username, returnedFromGoogle = false }: ChangePasswordDialogProps) {
  const info = useOwnPasswordInfo(open);
  // одна мутація на вікно: форма її запускає, кнопка в підвалі показує «зберігаємо»
  const change = useChangeOwnPassword();
  const setInitial = useSetInitialPassword();
  const [leaving, setLeaving] = useState(false);
  const [expired, setExpired] = useState(false);
  const data = info.data;
  const settingFirst = data?.status === 'NO_PASSWORD';
  const needsGoogle = settingFirst && data.reauthRequired === true;
  const formReady = data?.status === 'CHANGEABLE' || (settingFirst && !needsGoogle);
  const notice: ReauthNotice = expired ? 'expired' : returnedFromGoogle ? 'returned' : null;
  const actionLabel = settingFirst ? 'Встановити пароль' : 'Змінити пароль';

  const confirmWithGoogle = () => {
    setLeaving(true);
    void startGoogleReauth().catch(() => setLeaving(false));
  };

  const changeOpen = (next: boolean) => {
    if (!next) setExpired(false);
    onOpenChange(next);
  };

  return (
    <Dialog open={open} onOpenChange={changeOpen}>
      <DialogContent
        title={actionLabel}
        size="sm"
        footer={
          formReady ? (
            <>
              <DialogClose asChild>
                <Button>Скасувати</Button>
              </DialogClose>
              {/* підвал поза формою, тож кнопка звʼязана з нею через `form` */}
              <Button
                type="submit"
                form={FORM_ID}
                variant="primary"
                loading={settingFirst ? setInitial.isPending : change.isPending}
                loadingLabel="Зберігаємо пароль"
              >
                {actionLabel}
              </Button>
            </>
          ) : needsGoogle ? (
            <>
              <DialogClose asChild>
                <Button>Скасувати</Button>
              </DialogClose>
              <Button variant="primary" loading={leaving} loadingLabel="Переходимо до Google" onClick={confirmWithGoogle}>
                Підтвердити через Google
              </Button>
            </>
          ) : data ? (
            <DialogClose asChild>
              <Button>Закрити</Button>
            </DialogClose>
          ) : undefined
        }
      >
        {info.isPending ? (
          <LoadingRegion label="Завантажуємо">
            <div className="flex flex-col gap-4">
              <Skeleton className="h-11 w-full" />
              <Skeleton className="h-11 w-full" />
              <Skeleton className="h-11 w-full" />
            </div>
          </LoadingRegion>
        ) : info.isError ? (
          <ErrorState error={info.error} onRetry={() => void info.refetch()} retrying={info.isFetching} />
        ) : needsGoogle ? (
          <ReauthPanel notice={notice} />
        ) : settingFirst ? (
          <InitialPasswordForm
            info={info.data}
            username={username}
            setInitial={setInitial}
            onReauthRequired={() => setExpired(true)}
            onDone={() => changeOpen(false)}
          />
        ) : info.data.status === 'DEMO' ? (
          <p className="text-body text-secondary">Це спільний демонстраційний доступ: його пароль змінити не можна.</p>
        ) : (
          <PasswordForm info={info.data} username={username} change={change} onDone={() => changeOpen(false)} />
        )}
      </DialogContent>
    </Dialog>
  );
}

const REAUTH_NOTICE: Record<Exclude<ReauthNotice, null>, string> = {
  returned: 'Вхід через Google не підтверджено. Спробуйте ще раз тим самим обліковим записом Google.',
  expired: 'Підтвердження через Google застаріло. Підтвердьте вхід ще раз.',
};

function ReauthPanel({ notice }: { notice: ReauthNotice }) {
  return (
    <div className="flex flex-col gap-3">
      <p className="text-body text-secondary">
        Ви входите через Google, пароля поки немає. Можна додати пароль: тоді входитимете і через Google, і за логіном
        із паролем.
      </p>
      <p className="text-body text-secondary">
        Спершу підтвердьте, що це ви: увійдіть у Google ще раз, і ми повернемо вас сюди.
      </p>
      {notice ? <ServerAlert message={REAUTH_NOTICE[notice]} /> : null}
    </div>
  );
}

function ServerAlert({ message }: { message: string }) {
  return (
    <p role="alert" className="flex items-start gap-2 rounded-control border border-cancel bg-danger-subtle px-3 py-2 text-ui text-danger">
      <CircleExclamationPoint aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
      {message}
    </p>
  );
}

function schemaFor(info: OwnPasswordInfoDto) {
  return z
    .object({
      currentPassword: z.string().min(1, 'Введіть поточний пароль'),
      newPassword: z
        .string()
        .min(info.minLength, `Щонайменше ${info.minLength} символів`)
        .max(info.maxLength, `Не більше ${info.maxLength} символів`),
      confirm: z.string().min(1, 'Повторіть новий пароль'),
    })
    .refine((values) => values.newPassword === values.confirm, { path: ['confirm'], error: 'Паролі не збігаються' })
    .refine((values) => values.newPassword === '' || values.newPassword !== values.currentPassword, {
      path: ['newPassword'],
      error: 'Новий пароль має відрізнятися від поточного',
    });
}

type FormValues = z.infer<ReturnType<typeof schemaFor>>;

interface PasswordFormProps {
  info: OwnPasswordInfoDto;
  username?: string;
  change: ReturnType<typeof useChangeOwnPassword>;
  onDone: () => void;
}

function PasswordForm({ info, username, change, onDone }: PasswordFormProps) {
  const [show, setShow] = useState(false);
  const form = useForm<FormValues>({
    resolver: zodResolver(schemaFor(info)),
    defaultValues: { currentPassword: '', newPassword: '', confirm: '' },
    mode: 'onTouched',
  });
  const { errors } = form.formState;

  const submit = form.handleSubmit(async (values) => {
    try {
      await change.mutateAsync({ currentPassword: values.currentPassword, newPassword: values.newPassword });
      notify.success('Пароль змінено', { description: 'На інших пристроях доведеться увійти знову.' });
      onDone();
    } catch (error) {
      if (!isApiError(error)) {
        form.setError('root.server', { message: 'Щось пішло не так. Спробуйте ще раз.' });
        return;
      }
      const code = authProblemCode(error);
      if (code === 'current-password-wrong') {
        form.setError('currentPassword', { message: 'Поточний пароль неправильний' }, { shouldFocus: true });
      } else if (code === 'password-rejected') {
        form.setError('newPassword', { message: error.message }, { shouldFocus: true });
      } else if (code === 'too-many-attempts') {
        form.setError('root.server', { message: 'Забагато невдалих спроб. Зачекайте кілька хвилин і спробуйте ще раз.' });
      } else if (error.isUnauthorized) {
        form.setError('root.server', { message: 'Сесія входу завершилася. Увійдіть знову й повторіть.' });
      } else {
        form.setError('root.server', { message: error.message });
      }
    }
  });

  return (
    <form id={FORM_ID} noValidate onSubmit={(event) => void submit(event)} className="flex flex-col gap-4">
      {/* менеджер паролів має знати, для якого логіна новий пароль */}
      {username ? <input type="text" hidden readOnly autoComplete="username" value={username} /> : null}
      <Field label="Поточний пароль" required error={errors.currentPassword?.message}>
        <Input {...form.register('currentPassword')} type={show ? 'text' : 'password'} autoComplete="current-password" />
      </Field>
      <Field label="Новий пароль" required error={errors.newPassword?.message} hint={`Щонайменше ${info.minLength} символів`}>
        <Input
          {...form.register('newPassword')}
          type={show ? 'text' : 'password'}
          autoComplete="new-password"
          endSlot={<ShowToggle show={show} onToggle={() => setShow((value) => !value)} />}
        />
      </Field>
      <Field label="Повторіть новий пароль" required error={errors.confirm?.message}>
        <Input {...form.register('confirm')} type={show ? 'text' : 'password'} autoComplete="new-password" />
      </Field>
      {errors.root?.server?.message ? <ServerAlert message={errors.root.server.message} /> : null}
    </form>
  );
}

function initialSchemaFor(info: OwnPasswordInfoDto) {
  return z
    .object({
      newPassword: z
        .string()
        .min(info.minLength, `Щонайменше ${info.minLength} символів`)
        .max(info.maxLength, `Не більше ${info.maxLength} символів`),
      confirm: z.string().min(1, 'Повторіть пароль'),
    })
    .refine((values) => values.newPassword === values.confirm, { path: ['confirm'], error: 'Паролі не збігаються' });
}

type InitialValues = z.infer<ReturnType<typeof initialSchemaFor>>;

interface InitialPasswordFormProps {
  info: OwnPasswordInfoDto;
  username?: string;
  setInitial: ReturnType<typeof useSetInitialPassword>;
  /** Сервіс входу вже не вважає підтвердження свіжим: вікно знову просить Google. */
  onReauthRequired: () => void;
  onDone: () => void;
}

/** Перший пароль після свіжого підтвердження через Google: без поточного пароля, бо його немає. */
function InitialPasswordForm({ info, username, setInitial, onReauthRequired, onDone }: InitialPasswordFormProps) {
  const queryClient = useQueryClient();
  const [show, setShow] = useState(false);
  const form = useForm<InitialValues>({
    resolver: zodResolver(initialSchemaFor(info)),
    defaultValues: { newPassword: '', confirm: '' },
    mode: 'onTouched',
  });
  const { errors } = form.formState;

  const submit = form.handleSubmit(async (values) => {
    try {
      await setInitial.mutateAsync({ newPassword: values.newPassword });
      await queryClient.invalidateQueries({ queryKey: accountKeys.password() });
      notify.success('Пароль встановлено', {
        description: 'Тепер можна входити і через Google, і з паролем. На інших пристроях доведеться увійти знову.',
      });
      onDone();
    } catch (error) {
      if (!isApiError(error)) {
        form.setError('root.server', { message: 'Щось пішло не так. Спробуйте ще раз.' });
        return;
      }
      const code = authProblemCode(error);
      if (code === 'reauth-required') {
        onReauthRequired();
        await queryClient.invalidateQueries({ queryKey: accountKeys.password() });
      } else if (code === 'password-already-set') {
        // пароль уже є (встановили в іншій вкладці): вікно перейде до звичайної зміни
        await queryClient.invalidateQueries({ queryKey: accountKeys.password() });
      } else if (code === 'password-rejected') {
        form.setError('newPassword', { message: error.message }, { shouldFocus: true });
      } else if (code === 'too-many-attempts') {
        form.setError('root.server', { message: 'Забагато невдалих спроб. Зачекайте кілька хвилин і спробуйте ще раз.' });
      } else if (error.isUnauthorized) {
        form.setError('root.server', { message: 'Сесія входу завершилася. Увійдіть знову й повторіть.' });
      } else {
        form.setError('root.server', { message: error.message });
      }
    }
  });

  return (
    <form id={FORM_ID} noValidate onSubmit={(event) => void submit(event)} className="flex flex-col gap-4">
      {username ? <input type="text" hidden readOnly autoComplete="username" value={username} /> : null}
      <p className="text-body text-secondary">
        Вхід через Google підтверджено. Задайте пароль{username ? <> для логіна <span className="font-[650] text-primary">{username}</span></> : null}:
        далі входитимете і через Google, і з паролем.
      </p>
      <Field label="Новий пароль" required error={errors.newPassword?.message} hint={`Щонайменше ${info.minLength} символів`}>
        <Input
          {...form.register('newPassword')}
          type={show ? 'text' : 'password'}
          autoComplete="new-password"
          endSlot={<ShowToggle show={show} onToggle={() => setShow((value) => !value)} />}
        />
      </Field>
      <Field label="Повторіть пароль" required error={errors.confirm?.message}>
        <Input {...form.register('confirm')} type={show ? 'text' : 'password'} autoComplete="new-password" />
      </Field>
      {errors.root?.server?.message ? <ServerAlert message={errors.root.server.message} /> : null}
    </form>
  );
}

function ShowToggle({ show, onToggle }: { show: boolean; onToggle: () => void }) {
  return (
    <IconButton
      label={show ? 'Сховати паролі' : 'Показати паролі'}
      size="sm"
      pressed={show}
      icon={show ? <EyeOff aria-hidden="true" className="size-4" /> : <Eye aria-hidden="true" className="size-4" />}
      onClick={onToggle}
    />
  );
}
