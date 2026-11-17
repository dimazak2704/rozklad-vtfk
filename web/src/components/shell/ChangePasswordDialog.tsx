import { zodResolver } from '@hookform/resolvers/zod';
import { CircleExclamationPoint, Eye, EyeOff } from 'lucide-react';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { authProblemCode, useChangeOwnPassword, useOwnPasswordInfo, type OwnPasswordInfoDto } from '@/api/account';
import { isApiError } from '@/api/errors';
import { Button, Dialog, DialogClose, DialogContent, ErrorState, Field, IconButton, Input, LoadingRegion, Skeleton, notify } from '@/components/ui';

const FORM_ID = 'own-password-form';

interface ChangePasswordDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** Логін для менеджера паролів (приховане поле `autocomplete="username"`). */
  username?: string;
}

/**
 * «Змінити пароль» з меню користувача: поточний пароль, новий і повтор. Правила нового пароля — `PasswordPolicy`
 * сервісу входу (довжину він віддає разом зі станом); решту (поширений, логін, той самий) каже сервер. Після зміни
 * інші пристрої виходять: їхні сесії входу сервіс завершує, ця лишається.
 * Без пароля (вхід лише через Google) і для спільного демо-доступу форми немає — лише пояснення.
 */
export function ChangePasswordDialog({ open, onOpenChange, username }: ChangePasswordDialogProps) {
  const info = useOwnPasswordInfo(open);
  // одна мутація на вікно: форма її запускає, кнопка в підвалі показує «зберігаємо»
  const change = useChangeOwnPassword();
  const changeable = info.data?.status === 'CHANGEABLE';
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent
        title="Змінити пароль"
        size="sm"
        footer={
          changeable ? (
            <>
              <DialogClose asChild>
                <Button>Скасувати</Button>
              </DialogClose>
              {/* підвал поза формою, тож кнопка звʼязана з нею через `form` */}
              <Button type="submit" form={FORM_ID} variant="primary" loading={change.isPending} loadingLabel="Зберігаємо пароль">
                Змінити пароль
              </Button>
            </>
          ) : info.data ? (
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
        ) : info.data.status === 'NO_PASSWORD' ? (
          <p className="text-body text-secondary">
            Ви входите через Google, тож пароля в цього облікового запису немає. Якщо потрібен вхід за логіном і паролем,
            попросіть у навчальній частині посилання для пароля.
          </p>
        ) : info.data.status === 'DEMO' ? (
          <p className="text-body text-secondary">Це спільний демонстраційний доступ: його пароль змінити не можна.</p>
        ) : (
          <PasswordForm info={info.data} username={username} change={change} onDone={() => onOpenChange(false)} />
        )}
      </DialogContent>
    </Dialog>
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

  const toggle = (
    <IconButton
      label={show ? 'Сховати паролі' : 'Показати паролі'}
      size="sm"
      pressed={show}
      icon={show ? <EyeOff aria-hidden="true" className="size-4" /> : <Eye aria-hidden="true" className="size-4" />}
      onClick={() => setShow((value) => !value)}
    />
  );

  return (
    <form id={FORM_ID} noValidate onSubmit={(event) => void submit(event)} className="flex flex-col gap-4">
      {/* менеджер паролів має знати, для якого логіна новий пароль */}
      {username ? <input type="text" hidden readOnly autoComplete="username" value={username} /> : null}
      <Field label="Поточний пароль" required error={errors.currentPassword?.message}>
        <Input {...form.register('currentPassword')} type={show ? 'text' : 'password'} autoComplete="current-password" />
      </Field>
      <Field label="Новий пароль" required error={errors.newPassword?.message} hint={`Щонайменше ${info.minLength} символів`}>
        <Input {...form.register('newPassword')} type={show ? 'text' : 'password'} autoComplete="new-password" endSlot={toggle} />
      </Field>
      <Field label="Повторіть новий пароль" required error={errors.confirm?.message}>
        <Input {...form.register('confirm')} type={show ? 'text' : 'password'} autoComplete="new-password" />
      </Field>
      {errors.root?.server?.message ? (
        <p role="alert" className="flex items-start gap-2 rounded-control border border-cancel bg-danger-subtle px-3 py-2 text-ui text-danger">
          <CircleExclamationPoint aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
          {errors.root.server.message}
        </p>
      ) : null}
    </form>
  );
}
