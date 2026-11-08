import { ErrorResponse, ErrorTimeout } from 'oidc-client-ts';
import { describe, expect, it } from 'vitest';
import { callbackErrorText, loginErrorText } from './authErrors';

/** Як react-oidc-context передає помилку: копія name/message і сама помилка в innerError. */
const wrapped = (error: Error) => ({ name: error.name, message: error.message, innerError: error });

describe('помилки входу українською', () => {
  it('сервер входу недоступний: без «Failed to fetch»', () => {
    for (const message of ['Failed to fetch', 'NetworkError when attempting to fetch resource.', 'Load failed']) {
      expect(loginErrorText(new TypeError(message))).toBe('Немає звʼязку із сервером входу коледжу.');
      expect(loginErrorText(wrapped(new TypeError(message)))).toBe('Немає звʼязку із сервером входу коледжу.');
    }
  });

  it('тайм-аут, відмова сервера і невідома помилка — теж без тексту бібліотеки', () => {
    expect(loginErrorText(new ErrorTimeout('Network timed out'))).toBe('Сервер входу коледжу не відповів вчасно.');
    expect(loginErrorText(new ErrorResponse({ error: 'invalid_client', error_description: 'Invalid client credentials' }))).toBe(
      'Сервер входу коледжу відхилив запит.',
    );
    expect(loginErrorText(new Error('Invalid response Content-Type: text/html, from URL: http://kc'))).toBe(
      'Сервер входу коледжу не відповів або відхилив запит.',
    );
    expect(loginErrorText(undefined)).toBe('Сервер входу коледжу не відповів або відхилив запит.');
  });

  it('повернення з Keycloak: код помилки з адреси важливіший за state', () => {
    const noState = new Error('No matching state found in storage');
    expect(callbackErrorText(noState, '?error=access_denied&state=abc')).toBe('Вхід скасовано на сторінці входу коледжу.');
    expect(callbackErrorText(wrapped(new ErrorResponse({ error: 'access_denied', error_description: 'User denied' })))).toBe(
      'Вхід скасовано на сторінці входу коледжу.',
    );
    expect(callbackErrorText(noState, '?error=login_required&state=abc')).toBe('Сервер входу не підтвердив вхід.');
    expect(callbackErrorText(noState, '?code=x&state=abc')).toMatch(/^Посилання входу застаріло/);
    expect(callbackErrorText(new TypeError('Failed to fetch'))).toBe('Немає звʼязку із сервером входу коледжу.');
  });
});
