// Shared SDK stubs (bun's mock.module is process wide, so every test file installs the same ones).
import { mock } from 'bun:test';
import { writable } from 'svelte/store';

export const language = writable({ code: 'en-US' });

mock.module('@panomc/sdk', () => ({ viewComponent: (load) => ({ load }), PanoPlugin: class {} }));
mock.module('@panomc/sdk/utils/language', () => ({
  currentLanguage: language,
  _: writable((key) => key),
}));
mock.module('@panomc/sdk/toasts', () => ({ showToast: () => {} }));
mock.module('@panomc/sdk/utils/api', () => ({ default: {} }));
