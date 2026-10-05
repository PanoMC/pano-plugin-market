import { describe, expect, mock, test } from 'bun:test';

const calls = [];
mock.module('@panomc/sdk', () => ({
  PanoPlugin: class {
    constructor(pano) {
      this.pano = pano;
    }
  },
  viewComponent: (load) => ({ load }),
}));
mock.module('./panel/register.js', () => ({
  registerPanel: (pano) => calls.push(['panel', pano]),
}));
mock.module('./theme/register.js', () => ({
  registerTheme: (pano) => calls.push(['theme', pano]),
}));

const { default: Plugin } = await import('./main.js');

describe('main.js dispatch', () => {
  test('panel side only registers the panel', () => {
    calls.length = 0;
    const pano = { isPanel: true };
    new Plugin(pano).onLoad();
    expect(calls).toEqual([['panel', pano]]);
  });

  test('theme side only registers the theme', () => {
    calls.length = 0;
    const pano = { isPanel: false };
    new Plugin(pano).onLoad();
    expect(calls).toEqual([['theme', pano]]);
  });
});
