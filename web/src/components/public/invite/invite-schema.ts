import { z } from 'zod';

/** Логін: латинські малі літери, цифри, «.», «-», «_»; першою — літера (так само перевіряє сервер). */
export const USERNAME_PATTERN = /^[a-z][a-z0-9._-]*$/;

const LETTER = /\p{L}/u;
const DIGIT = /\d/;

/** Довжина пароля, як у сервісі входу (OWASP ASVS 2.1.1): від 12 до 64 символів. */
export const PASSWORD_MIN = 12;
export const PASSWORD_MAX = 64;

export const PASSWORD_RULES = [
  { id: 'length', label: `щонайменше ${PASSWORD_MIN} символів`, test: (value: string) => value.length >= PASSWORD_MIN },
  { id: 'letter', label: 'хоча б одна літера', test: (value: string) => LETTER.test(value) },
  { id: 'digit', label: 'хоча б одна цифра', test: (value: string) => DIGIT.test(value) },
] as const;

export const inviteSchema = z
  .object({
    username: z
      .string()
      .trim()
      .min(3, 'Щонайменше 3 символи')
      .max(32, 'Не більше 32 символів')
      .regex(/^[a-z]/, 'Почніть з латинської малої літери')
      .regex(USERNAME_PATTERN, 'Лише латинські малі літери, цифри, крапка, дефіс і підкреслення'),
    password: z
      .string()
      .min(PASSWORD_MIN, `Щонайменше ${PASSWORD_MIN} символів`)
      .max(PASSWORD_MAX, `Не більше ${PASSWORD_MAX} символів`)
      .regex(LETTER, 'Додайте хоча б одну літеру')
      .regex(DIGIT, 'Додайте хоча б одну цифру'),
    confirm: z.string().min(1, 'Повторіть пароль'),
  })
  .refine((values) => values.password === values.confirm, { path: ['confirm'], error: 'Паролі не збігаються' });

export type InviteFormValues = z.infer<typeof inviteSchema>;
