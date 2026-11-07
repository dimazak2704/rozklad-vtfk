import { describe, expect, it } from 'vitest';
import { backoffDelay, createSseParser, type SseMessage } from './sse';

function collect() {
  const messages: SseMessage[] = [];
  const retries: number[] = [];
  const parser = createSseParser({ onMessage: (m) => messages.push(m), onRetry: (ms) => retries.push(ms) });
  return { parser, messages, retries };
}

describe('createSseParser', () => {
  it('розбирає подію з типом, id і JSON-даними', () => {
    const { parser, messages } = collect();
    parser.push('event: change.updated\nid: 42\ndata: {"id":"c1"}\n\n');
    expect(messages).toEqual([{ event: 'change.updated', id: '42', data: '{"id":"c1"}' }]);
    expect(parser.lastEventId()).toBe('42');
  });

  it('склеює багаторядкові data і пропускає коментарі', () => {
    const { parser, messages } = collect();
    parser.push(': keep-alive\ndata: рядок 1\ndata: рядок 2\n\n');
    expect(messages).toEqual([{ event: 'message', data: 'рядок 1\nрядок 2' }]);
  });

  it('збирає подію з довільно порізаних шматків і \\r\\n', () => {
    const { parser, messages } = collect();
    for (const chunk of ['event: pi', 'ng\r', '\ndata: ', 'x\r\n', '\r\n']) parser.push(chunk);
    expect(messages).toEqual([{ event: 'ping', data: 'x' }]);
  });

  it('не відправляє подію без data', () => {
    const { parser, messages } = collect();
    parser.push('event: ping\n\n');
    expect(messages).toHaveLength(0);
  });

  it('передає retry лише як число', () => {
    const { parser, retries } = collect();
    parser.push('retry: 5000\nretry: abc\n\n');
    expect(retries).toEqual([5000]);
  });

  it('поле без двокрапки та пробіл після двокрапки', () => {
    const { parser, messages } = collect();
    parser.push('data\n\ndata:без пробілу\n\n');
    expect(messages.map((m) => m.data)).toEqual(['', 'без пробілу']);
  });
});

describe('backoffDelay', () => {
  it('росте експоненційно до межі', () => {
    const noJitter = () => 0.5;
    expect(backoffDelay(0, 1000, 30_000, noJitter)).toBe(1000);
    expect(backoffDelay(3, 1000, 30_000, noJitter)).toBe(8000);
    expect(backoffDelay(10, 1000, 30_000, noJitter)).toBe(30_000);
  });

  it('додає не більше ±20% тремтіння', () => {
    expect(backoffDelay(2, 1000, 30_000, () => 0)).toBe(3200);
    expect(backoffDelay(2, 1000, 30_000, () => 1)).toBe(4800);
  });
});
