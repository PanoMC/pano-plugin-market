// Pure rules of the goals page and GoalModal (13 §21). No Svelte and no SDK import.
import { parseInteger } from './format.js';

export const METRICS = ['REVENUE', 'ORDERS', 'PRODUCT_SALES'];
export const PERIODS = ['ONE_TIME', 'WEEKLY', 'MONTHLY'];

export const NAME_MAX = 255;
export const DESCRIPTION_MAX = 512;

/** Empty form of the modal; money / integer targets are typed text or a Number (MoneyInput). */
export function blankGoal() {
  return {
    name: '',
    description: '',
    metric: 'REVENUE',
    productIds: [],
    target: null,
    period: 'ONE_TIME',
    startsAt: null,
    endsAt: null,
    active: true,
    showOnStore: true,
  };
}

/** Form values of an existing goal row. */
export function goalToForm(goal) {
  return {
    ...blankGoal(),
    name: goal?.name ?? '',
    description: goal?.description ?? '',
    metric: METRICS.includes(goal?.metric) ? goal.metric : 'REVENUE',
    productIds: Array.isArray(goal?.productIds) ? goal.productIds.map(Number) : [],
    target: goal?.target ?? null,
    period: PERIODS.includes(goal?.period) ? goal.period : 'ONE_TIME',
    startsAt: goal?.startsAt ?? null,
    endsAt: goal?.endsAt ?? null,
    active: goal?.status ? goal.status === 'ACTIVE' : true,
    showOnStore: goal?.showOnStore !== false,
  };
}

/** Target as a Number or NaN: money (> 0) for REVENUE, integer >= 1 otherwise. */
export function targetValue(form) {
  const raw = form.target;
  if (raw === null || raw === undefined || raw === '') return NaN;
  if (form.metric === 'REVENUE') {
    const n = Number(raw);
    return Number.isFinite(n) && n > 0 ? n : NaN;
  }
  const n = parseInteger(String(raw), { min: 1 });
  return n === null ? NaN : n;
}

/** field -> error key (below pages.goals.field-errors) for every invalid field; {} = valid. */
export function validateGoal(form) {
  const errors = {};
  const name = String(form.name ?? '').trim();
  if (name === '') errors.name = 'REQUIRED';
  else if (name.length > NAME_MAX) errors.name = 'TOO_LONG';
  if (String(form.description ?? '').length > DESCRIPTION_MAX) errors.description = 'TOO_LONG';
  if (!METRICS.includes(form.metric)) errors.metric = 'REQUIRED';
  if (form.metric === 'PRODUCT_SALES' && !(form.productIds?.length > 0)) errors.productIds = 'REQUIRED';
  if (Number.isNaN(targetValue(form))) errors.target = 'INVALID';
  if (!PERIODS.includes(form.period)) errors.period = 'REQUIRED';
  const { startsAt, endsAt } = form;
  if (startsAt !== null && endsAt !== null && startsAt >= endsAt) errors.endsAt = 'BEFORE_START';
  return errors;
}

/** Request body of POST / PUT /goals; productIds only for PRODUCT_SALES. */
export function goalBody(form) {
  const body = {
    name: String(form.name).trim(),
    description: String(form.description ?? '').trim(),
    metric: form.metric,
    target: targetValue(form),
    period: form.period,
    startsAt: form.startsAt ?? null,
    endsAt: form.endsAt ?? null,
    status: form.active ? 'ACTIVE' : 'INACTIVE',
    showOnStore: form.showOnStore === true,
  };
  body.productIds = form.metric === 'PRODUCT_SALES' ? form.productIds.map(Number) : [];
  return body;
}

/** Progress bar width: the server's percent clamped to 0..100; anything else is 0. */
export function progressPercent(goal) {
  const n = Number(goal?.percent);
  if (!Number.isFinite(n)) return 0;
  return Math.min(100, Math.max(0, Math.round(n)));
}
