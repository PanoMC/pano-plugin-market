// The frame is the gateway's page: it has no empty state.
export const notApplicable = ['empty'];

/** @type {import('@panomc/plugin-kit').Samples} */
export default {
  filled: {
    props: { iframe: { url: 'https://example.com/', heightPx: 480 }, title: 'Credit card' },
  },
  loading: {
    label: 'Waiting for the gateway script',
    props: {
      iframe: { url: 'https://example.com/', scripts: ['https://example.com/resizer.js'] },
      title: 'Credit card',
    },
  },
  error: {
    props: {
      iframe: { url: 'https://example.com/', scripts: ['http://example.com/resizer.js'] },
      title: 'Credit card',
    },
  },
};
