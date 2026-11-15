import { describe, expect, it } from 'vitest';
import { leftOutIsGap, leftOutOf, leftOutText } from './left-out';

describe('leftOutText', () => {
  it('нічого не лишилось — null', () => {
    expect(leftOutText({ undecided: 0, pending: 0, declined: 0 })).toBeNull();
  });

  it('пари без рішення рахуються окремо від очікувань і відмов', () => {
    expect(leftOutText({ undecided: 3, pending: 1, declined: 1 })).toBe(
      '3 пари без рішення, 1 заміна ще чекає підтвердження і 1 без нового кандидата після відмови',
    );
    expect(leftOutText({ undecided: 1, pending: 0, declined: 0 })).toBe('1 пара без рішення');
    expect(leftOutText({ undecided: 0, pending: 2, declined: 0 })).toBe('2 заміни ще чекають підтвердження');
  });

  it('червоне — лише коли є пари без рішення чи відмови', () => {
    expect(leftOutIsGap({ undecided: 1, pending: 0, declined: 0 })).toBe(true);
    expect(leftOutIsGap({ undecided: 0, pending: 0, declined: 1 })).toBe(true);
    expect(leftOutIsGap({ undecided: 0, pending: 3, declined: 0 })).toBe(false);
  });

  it('панель core без нового поля (старий сервер) не ламає попередження', () => {
    expect(leftOutOf({ pending: 1, declined: 0 } as never)).toEqual({ undecided: 0, pending: 1, declined: 0 });
    expect(leftOutOf(undefined)).toEqual({ undecided: 0, pending: 0, declined: 0 });
  });
});
