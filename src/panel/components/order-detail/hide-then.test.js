import { describe, expect, test } from 'bun:test';
import { hideThen, submitLocked } from './hide-then.js';

/** A modal element and a Bootstrap whose hide() fires `hidden.bs.modal` after `delay` ms (never when delay is null). */
function fixture({ shown = true, delay = 10 } = {}) {
  const listeners = [];
  const element = {
    classList: { contains: (name) => name === 'show' && shown },
    addEventListener: (type, fn) => listeners.push({ type, fn }),
  };
  const calls = [];
  const bootstrap = {
    Modal: {
      getOrCreateInstance: () => ({
        hide: () => {
          calls.push('hide');
          if (delay !== null)
            setTimeout(
              () => listeners.filter((l) => l.type === 'hidden.bs.modal').forEach((l) => l.fn()),
              delay,
            );
        },
      }),
    },
  };

  return { element, bootstrap, calls };
}

describe('hideThen', () => {
  test('runs done only after the modal reported hidden, not right after hide()', async () => {
    const { element, bootstrap, calls } = fixture({ delay: 40 });
    const order = [];

    hideThen(element, bootstrap, () => order.push('done'), { timeout: 1000 });
    order.push('after-call');
    expect(calls).toEqual(['hide']);
    expect(order).toEqual(['after-call']);
    await new Promise((resolve) => setTimeout(resolve, 80));
    expect(order).toEqual(['after-call', 'done']);
  });

  test('runs done once even when both the event and the timer fire', async () => {
    const { element, bootstrap } = fixture({ delay: 10 });
    let runs = 0;

    hideThen(element, bootstrap, () => runs++, { timeout: 30 });
    await new Promise((resolve) => setTimeout(resolve, 100));
    expect(runs).toBe(1);
  });

  test('the timer is the way out when hidden never arrives', async () => {
    const { element, bootstrap } = fixture({ delay: null });
    let runs = 0;

    hideThen(element, bootstrap, () => runs++, { timeout: 30 });
    expect(runs).toBe(0);
    await new Promise((resolve) => setTimeout(resolve, 80));
    expect(runs).toBe(1);
  });

  test('a modal that is not shown, a missing element or a missing bootstrap runs done at once', () => {
    let runs = 0;
    const closed = fixture({ shown: false });

    hideThen(closed.element, closed.bootstrap, () => runs++);
    expect(runs).toBe(1);
    expect(closed.calls).toEqual([]);
    hideThen(null, closed.bootstrap, () => runs++);
    hideThen(closed.element, undefined, () => runs++);
    expect(runs).toBe(3);
  });
});

describe('submitLocked', () => {
  test('a submit is refused while the request is in flight and from the successful response until the modal is reopened', () => {
    expect(submitLocked({ saving: false, closing: false })).toBe(false);
    expect(submitLocked({ saving: true, closing: false })).toBe(true);
    expect(submitLocked({ saving: false, closing: true })).toBe(true);
    expect(submitLocked({ saving: true, closing: true })).toBe(true);
    expect(submitLocked()).toBe(false);
  });

  test('the fade-out window of hideThen stays locked until done runs, so a second click cannot post the form again', async () => {
    const listeners = [];
    const element = {
      classList: { contains: (name) => name === 'show' },
      addEventListener: (type, fn) => listeners.push({ type, fn }),
    };
    const bootstrap = {
      Modal: {
        getOrCreateInstance: () => ({
          hide: () => setTimeout(() => listeners.forEach((l) => l.fn()), 30),
        }),
      },
    };

    // what the modals do: submit() posts at most once while the guard holds
    let closing = false;
    let posts = 0;
    const submit = () => {
      if (submitLocked({ saving: false, closing })) return;
      posts += 1;
      closing = true;
      hideThen(element, bootstrap, () => {});
    };

    submit();
    submit(); // second click during the fade-out
    await new Promise((r) => setTimeout(r, 10));
    submit(); // Enter in the still-mounted form

    expect(posts).toBe(1);
  });
});
