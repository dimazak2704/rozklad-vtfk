import { zodResolver } from '@hookform/resolvers/zod';
import { Check, CircleExclamationPoint, Eye, EyeOff } from 'lucide-react';
import { useState } from 'react';
import { useForm, useWatch } from 'react-hook-form';
import { isApiError } from '@/api/errors';
import { useAcceptInvite } from '@/api/public';
import { Button, Field, IconButton, Input } from '@/components/ui';
import { cn } from '@/lib/cn';
import { inviteSchema, PASSWORD_RULES, type InviteFormValues } from './invite-schema';

const FIELDS: ReadonlyArray<keyof InviteFormValues> = ['username', 'password', 'confirm'];

interface InviteFormProps {
  token: string;
  /** Обліковий запис створено. */
  onAccepted: (username: string) => void;
  /** Сервер каже, що запрошення вже недійсне (410). */
  onGone: (message: string) => void;
}

/**
 * Реєстрація за запрошенням: логін, пароль і повтор. Перевірка — zod на клієнті, остаточна — на сервері:
 * зайнятий логін (409) і помилки полів (400) показуються під полями, введене не губиться.
 */
export function InviteForm({ token, onAccepted, onGone }: InviteFormProps) {
  const accept = useAcceptInvite(token);
  const [showPassword, setShowPassword] = useState(false);
  const form = useForm<InviteFormValues>({
    resolver: zodResolver(inviteSchema),
    defaultValues: { username: '', password: '', confirm: '' },
    mode: 'onTouched',
  });
  const { errors, isSubmitting } = form.formState;
  const password = useWatch({ control: form.control, name: 'password' });

  const submit = form.handleSubmit(async (values) => {
    try {
      const result = await accept.mutateAsync({ username: values.username, password: values.password });
      onAccepted(result.username);
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
        const message = fieldErrors[field];
        if (message) {
          form.setError(field, { message }, { shouldFocus: !mapped });
          mapped = true;
        }
      }
      if (error.status === 409 && !mapped) {
        form.setError('username', { message: 'Цей логін уже зайнятий — оберіть інший' }, { shouldFocus: true });
        mapped = true;
      }
      if (!mapped) form.setError('root.server', { message: error.message });
    }
  });

  return (
    <form noValidate onSubmit={(event) => void submit(event)} className="flex flex-col gap-4">
      <Field label="Логін" required error={errors.username?.message} hint="Латинськими малими літерами, наприклад kolesnyk.a — з ним ви входитимете.">
        <Input {...form.register('username')} autoComplete="username" autoCapitalize="none" spellCheck={false} inputMode="email" />
      </Field>
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

      <Button type="submit" variant="primary" size="lg" loading={isSubmitting} loadingLabel="Створюємо обліковий запис" className="mt-1">
        Створити обліковий запис
      </Button>
    </form>
  );
}
