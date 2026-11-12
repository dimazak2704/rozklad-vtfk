import { act, renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { noteResponseSource, resetSavedCopies, savedCopyTimeLabel, useAnySavedCopy, useSavedCopy } from './saved-copy';
import { SAVED_COPY_HEADER, savedCopyPlugin } from './saved-copy-plugin';

type Callback = typeof savedCopyPlugin.cachedResponseWillBeUsed;

/** Так плагін потрапляє в sw.js: workbox-build пише функцію текстом, без змінних модуля навколо. */
function asInSw(): Callback {
  // eslint-disable-next-line @typescript-eslint/no-implied-eval
  const factory = new Function(`return (${savedCopyPlugin.cachedResponseWillBeUsed.toString()});`) as () => Callback;
  return factory();
}

describe('savedCopyPlugin (Service Worker)', () => {
  it('позначає копію з кешу датою її відповіді й не губить тіло та статус', async () => {
    const cached = new Response('{"days":[]}', {
      status: 200,
      headers: { 'Content-Type': 'application/json', Date: 'Tue, 10 Nov 2026 06:15:00 GMT' },
    });
    const marked = await asInSw()({ cachedResponse: cached });
    expect(marked?.headers.get(SAVED_COPY_HEADER)).toBe('Tue, 10 Nov 2026 06:15:00 GMT');
    expect(marked?.headers.get('Content-Type')).toBe('application/json');
    expect(marked?.status).toBe(200);
    expect(await marked?.text()).toBe('{"days":[]}');
  });

  it('без заголовка Date копія все одно позначена; прострочену (null) пропускає далі як є', async () => {
    const marked = await asInSw()({ cachedResponse: new Response('x') });
    expect(marked?.headers.get(SAVED_COPY_HEADER)).toBe('unknown');
    expect(await asInSw()({ cachedResponse: null })).toBeNull();
  });
});

describe('noteResponseSource і useSavedCopy', () => {
  afterEach(() => resetSavedCopies());

  it('копія з кешу видна за адресою, а свіжа відповідь мережі позначку прибирає', () => {
    const url = '/api/public/schedule/group/g1?from=2026-11-09&to=2026-11-13';
    const { result } = renderHook(() => useSavedCopy(url));
    expect(result.current).toEqual({ fromCache: false });

    act(() => noteResponseSource(url, new Headers({ [SAVED_COPY_HEADER]: 'Tue, 10 Nov 2026 06:15:00 GMT' })));
    expect(result.current).toEqual({ fromCache: true, savedAt: Date.parse('2026-11-10T06:15:00Z') });

    // відповідь за іншою адресою цю позначку не чіпає
    act(() => noteResponseSource('/api/public/meta', new Headers()));
    expect(result.current.fromCache).toBe(true);

    act(() => noteResponseSource(url, new Headers()));
    expect(result.current).toEqual({ fromCache: false });
  });

  it('невідома дата копії — позначка без часу', () => {
    const url = '/api/public/changes?date=2026-11-12';
    act(() => noteResponseSource(url, new Headers({ [SAVED_COPY_HEADER]: 'unknown' })));
    const { result } = renderHook(() => useSavedCopy(url));
    expect(result.current).toEqual({ fromCache: true, savedAt: null });
  });
});

describe('useAnySavedCopy', () => {
  afterEach(() => resetSavedCopies());
  const a = '/api/public/changes?date=2026-11-12';
  const b = '/api/public/schedule/group/g1?from=2026-11-12&to=2026-11-12';

  it('хоч одна копія — так; дата — найстарішої; невідома дата робить невідомою й загальну', () => {
    const { result } = renderHook(() => useAnySavedCopy([a, undefined, b]));
    expect(result.current).toEqual({ fromCache: false });

    act(() => noteResponseSource(a, new Headers({ [SAVED_COPY_HEADER]: 'Thu, 12 Nov 2026 06:15:00 GMT' })));
    act(() => noteResponseSource(b, new Headers({ [SAVED_COPY_HEADER]: 'Tue, 10 Nov 2026 06:15:00 GMT' })));
    expect(result.current).toEqual({ fromCache: true, savedAt: Date.parse('2026-11-10T06:15:00Z') });

    act(() => noteResponseSource(a, new Headers({ [SAVED_COPY_HEADER]: 'unknown' })));
    expect(result.current).toEqual({ fromCache: true, savedAt: null });

    act(() => noteResponseSource(a, new Headers()));
    act(() => noteResponseSource(b, new Headers()));
    expect(result.current).toEqual({ fromCache: false });
  });
});

describe('savedCopyTimeLabel', () => {
  const now = new Date('2026-11-12T10:00:00Z');
  it('каже «сьогодні», «вчора» або дату, у київському часі', () => {
    expect(savedCopyTimeLabel(Date.parse('2026-11-12T06:15:00Z'), now)).toBe('сьогодні о 08:15');
    expect(savedCopyTimeLabel(Date.parse('2026-11-11T17:40:00Z'), now)).toBe('вчора о 19:40');
    expect(savedCopyTimeLabel(Date.parse('2026-11-05T06:15:00Z'), now)).toBe('5 листопада о 08:15');
  });
});
