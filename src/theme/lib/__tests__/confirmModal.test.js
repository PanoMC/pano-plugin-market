import { describe, expect, test } from 'bun:test';
import { createConfirmController } from '../confirmModal.js';

/** A stub of the host's bootstrap.Modal on a stub element: hide() is a no-op while `_isTransitioning`, like the real one. */
function setup() {
  const classes = new Set();
  const element = new EventTarget();
  element.classList = { contains: (c) => classes.has(c) };

  const calls = { confirm: 0, cancel: 0 };
  const modal = {
    _isTransitioning: false,
    hides: 0,
    hide() {
      this.hides += 1;
      if (this._isTransitioning || !classes.has('show')) return;
      classes.delete('show');
      element.dispatchEvent(new Event('hide.bs.modal'));
      this.finishHide();
    },
    // the fade-out ends
    finishHide() {
      element.dispatchEvent(new Event('hidden.bs.modal'));
    },
  };

  const ctrl = createConfirmController({
    getModal: () => modal,
    getElement: () => element,
    onconfirm: () => (calls.confirm += 1),
    oncancel: () => (calls.cancel += 1),
  });
  element.addEventListener('hide.bs.modal', () => ctrl.hideStarted());
  element.addEventListener('hidden.bs.modal', () => ctrl.hidden());

  return {
    calls,
    modal,
    ctrl,
    element,
    open: () => {
      ctrl.reset();
      classes.add('show');
    },
    // the fade-in is running: no 'show' class yet
    openFading: () => {
      ctrl.reset();
      modal._isTransitioning = true;
    },
    shown: () => {
      modal._isTransitioning = false;
      classes.add('show');
      element.dispatchEvent(new Event('shown.bs.modal'));
    },
    // X / Cancel / Esc: Bootstrap's own dismiss calls hide()
    dismiss: () => modal.hide(),
  };
}

describe('ConfirmModal controller', () => {
  test('confirm hides the modal and runs the action once, after it is hidden', () => {
    const t = setup();
    t.open();
    t.modal.finishHide = () => {
      expect(t.calls.confirm).toBe(0); // not yet: the fade-out has not ended
      t.element.dispatchEvent(new Event('hidden.bs.modal'));
    };
    t.ctrl.confirm();
    expect(t.calls).toEqual({ confirm: 1, cancel: 0 });
  });

  test('a double click on confirm runs the action once', () => {
    const t = setup();
    t.open();
    // the fade-out is slow: both clicks land before hidden fires
    t.modal.finishHide = () => {};
    t.ctrl.confirm();
    t.ctrl.confirm();
    t.element.dispatchEvent(new Event('hidden.bs.modal'));
    expect(t.calls).toEqual({ confirm: 1, cancel: 0 });
    expect(t.modal.hides).toBe(1);
  });

  test('confirm during the fade-in waits for shown, then runs the action once', () => {
    const t = setup();
    t.openFading();
    t.ctrl.confirm();
    t.ctrl.confirm();
    expect(t.modal.hides).toBe(0); // the real hide() would have been a no-op
    expect(t.calls).toEqual({ confirm: 0, cancel: 0 });
    t.shown();
    expect(t.modal.hides).toBe(1);
    expect(t.calls).toEqual({ confirm: 1, cancel: 0 });
  });

  test('a dismissal alone cancels and never runs the action', () => {
    const t = setup();
    t.open();
    t.dismiss();
    expect(t.calls).toEqual({ confirm: 0, cancel: 1 });
  });

  test('a dismiss during the fade-in does not run a confirm that was never clicked', () => {
    const t = setup();
    t.openFading();
    t.dismiss(); // no-op in Bootstrap: nothing happens
    t.shown();
    expect(t.calls).toEqual({ confirm: 0, cancel: 0 });
    t.dismiss();
    expect(t.calls).toEqual({ confirm: 0, cancel: 1 });
  });

  test('a confirm click on a modal that is already hiding because it was dismissed is ignored', () => {
    const t = setup();
    t.open();
    t.modal.finishHide = () => {}; // the fade-out is running
    t.dismiss();
    t.ctrl.confirm();
    t.element.dispatchEvent(new Event('hidden.bs.modal'));
    expect(t.calls).toEqual({ confirm: 0, cancel: 1 });
  });

  test('the next round starts clean after a confirm', () => {
    const t = setup();
    t.open();
    t.ctrl.confirm();
    expect(t.calls).toEqual({ confirm: 1, cancel: 0 });
    t.open();
    t.dismiss();
    expect(t.calls).toEqual({ confirm: 1, cancel: 1 });
  });

  test('show() clears a flag left by a confirm whose hide never completed', () => {
    const t = setup();
    t.openFading();
    t.ctrl.confirm(); // queued for shown, which never comes (modal disposed)
    t.modal._isTransitioning = false;
    t.open();
    t.dismiss();
    expect(t.calls).toEqual({ confirm: 0, cancel: 1 });
  });

  test('without Bootstrap the action runs at once', () => {
    const calls = { confirm: 0 };
    const ctrl = createConfirmController({
      getModal: () => undefined,
      getElement: () => undefined,
      onconfirm: () => (calls.confirm += 1),
      oncancel: () => {},
    });
    ctrl.confirm();
    ctrl.confirm();
    expect(calls.confirm).toBe(2); // each click is its own action: there is no modal round to guard
  });
});
