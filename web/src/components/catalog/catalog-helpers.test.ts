import { describe, expect, it } from 'vitest';
import { ApiError } from '@/api/errors';
import { disciplineLine, LINE_COUNT } from '@/lib/discipline-color';
import { LINE_SWATCHES, lineOf, swatchOf } from './discipline-palette';
import { applyServerErrors, blankToNull } from './forms';
import { shortNameOf } from './names';

describe('палітра ліній дисциплін', () => {
  it('кожен колір палітри веде назад до своєї лінії (і в каталозі, і в розкладі)', () => {
    expect(LINE_SWATCHES).toHaveLength(LINE_COUNT);
    for (const swatch of LINE_SWATCHES) {
      expect(disciplineLine({ id: 'будь-який', color: swatch.hex })).toBe(swatch.line);
      expect(swatchOf(swatch.hex.toUpperCase())?.line).toBe(swatch.line);
    }
  });

  it('без кольору лінія стала й залежить від id; некоректний hex ігнорується', () => {
    expect(lineOf('discipline-1', null)).toBe(lineOf('discipline-1', null));
    expect(lineOf('discipline-1', 'синій')).toBe(lineOf('discipline-1', null));
    expect(swatchOf('#123456')).toBeUndefined();
  });
});

describe('коротке імʼя викладача', () => {
  it('складає ініціали, як сервер', () => {
    expect(shortNameOf('Кренцін', 'Микола', 'Дмитрович')).toBe('Кренцін М.Д.');
    expect(shortNameOf('Непийвода', 'марія', null)).toBe('Непийвода М.');
    expect(shortNameOf('Прокопчук', null, null)).toBe('Прокопчук');
    expect(shortNameOf('', 'Микола', null)).toBe('');
  });
});

describe('помилки сервера у формі', () => {
  it('помилки відомих полів — під поля, решта — над формою', () => {
    const calls: Array<[string, string | undefined]> = [];
    const setError = (field: string, error: { message?: string }) => calls.push([field, error.message]);
    const error = new ApiError({
      status: 400,
      detail: 'Перевірте виділені поля.',
      errors: [
        { field: 'name', message: 'Назва аудиторії має містити літери або цифри.' },
        { field: 'buildingId', message: 'Корпус не знайдено.' },
      ],
    });
    expect(applyServerErrors<{ name: string }>(error, setError, ['name'])).toBe('Корпус не знайдено.');
    expect(calls).toEqual([['name', 'Назва аудиторії має містити літери або цифри.']]);
  });

  it('409 без полів — текстом над формою; поля через псевдоніми', () => {
    const conflict = new ApiError({ status: 409, detail: 'Аудиторія «301» уже існує.' });
    expect(applyServerErrors(conflict, () => undefined, [])).toBe('Аудиторія «301» уже існує.');
    const calls: string[] = [];
    const mapped = new ApiError({ status: 400, errors: [{ field: 'course', message: 'Курс не збігається з кодом групи (4).' }] });
    expect(applyServerErrors<{ code: string }>(mapped, (field) => calls.push(field), ['code'], { course: 'code' })).toBeNull();
    expect(calls).toEqual(['code']);
  });

  it('порожні рядки → null', () => {
    expect(blankToNull('  ')).toBeNull();
    expect(blankToNull(' 301 ')).toBe('301');
    expect(blankToNull(undefined)).toBeNull();
  });
});
