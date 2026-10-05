// Variant option axes and the cartesian variant generator of the product form (13 §8.3).
// Pure: no Svelte, no SDK import.
import { uuid } from './uuid.js';

export const MAX_AXES = 3;
export const MAX_AXIS_VALUES = 20;
export const MAX_VARIANTS = 100;

/**
 * `key` generated from a label: lower-case, accents stripped (Turkish letters included), everything else
 * outside `[a-z0-9_]` becomes `_`, runs and edges collapsed. Empty when the label has no usable
 * character.
 */
export function keyFromLabel(label) {
  const text = String(label ?? '')
    .toLocaleLowerCase('en-US')
    .replace(/ı/g, 'i')
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '');
  return text
    .replace(/[^a-z0-9_]+/g, '_')
    .replace(/_+/g, '_')
    .replace(/^_|_$/g, '');
}

/** `base` made unique against `taken` (a Set or array) by appending `_2`, `_3`, ... */
export function uniqueKey(base, taken) {
  const used = taken instanceof Set ? taken : new Set(taken);
  const root = base === '' ? 'option' : base;
  if (!used.has(root)) return root;
  let n = 2;
  while (used.has(`${root}_${n}`)) n++;
  return `${root}_${n}`;
}

/** An axis `{key, label, values: [{key, label}]}` without a label or without values is unusable. */
export function usableAxes(axes) {
  return (axes ?? [])
    .map((axis) => ({
      ...axis,
      values: (axis.values ?? []).filter((v) => String(v.label ?? '').trim() !== ''),
    }))
    .filter((axis) => String(axis.label ?? '').trim() !== '' && axis.values.length > 0);
}

/** `sig(v) = axes.map(a => v.optionValues[a.key]).join('|')`. */
export function signature(axes, optionValues) {
  const values = optionValues ?? {};
  return axes.map((axis) => values[axis.key] ?? '').join('|');
}

/** The product the cartesian product of `axes` would produce. */
export function combinationCount(axes) {
  return axes.reduce((n, axis) => n * axis.values.length, 1);
}

/** Every combination as `[{ optionValues, labels }]`, first axis varying slowest. */
export function combinations(axes) {
  let rows = [{ optionValues: {}, labels: [] }];
  for (const axis of axes) {
    const next = [];
    for (const row of rows)
      for (const value of axis.values)
        next.push({
          optionValues: { ...row.optionValues, [axis.key]: value.key },
          labels: [...row.labels, value.label],
        });
    rows = next;
  }
  return rows;
}

/** A variant row with every field of the table; `overrides` win. `_key` is a UI-only row identity. */
export function blankVariant(overrides = {}) {
  return {
    _key: uuid(),
    name: '',
    sku: '',
    optionValues: {},
    attributes: [],
    price: null,
    compareAtPrice: null,
    creditPrice: null,
    stock: null,
    weightGrams: null,
    periodCount: null,
    status: 'ACTIVE',
    position: 0,
    prices: [],
    imageFile: null,
    imageToken: '',
    imageFileName: null,
    removeImage: false,
    orphan: false,
    ...overrides,
  };
}

/**
 * Generates the variants of `axes`, keeping what already exists.
 *
 * - a combination whose signature matches an existing variant keeps that variant untouched
 *   (id, price, stock, ...);
 * - any other combination becomes `{ name: labels.join(' / '), optionValues, status: 'ACTIVE', position }`;
 * - existing variants whose signature is no longer produced are **kept** and flagged `orphan: true`
 *   (the admin removes them explicitly), after the generated ones;
 * - more than MAX_VARIANTS combinations refuse with `{ error: 'TOO_MANY', count }` and change
 *   nothing; no usable axis answers `{ error: 'NO_OPTIONS' }`.
 *
 * Success: `{ variants, added, kept, orphans }` (counts). `existing` is never mutated.
 */
export function generateVariants(axes, existing = []) {
  const usable = usableAxes(axes);
  if (usable.length === 0) return { error: 'NO_OPTIONS' };
  const count = combinationCount(usable);
  if (count > MAX_VARIANTS) return { error: 'TOO_MANY', count };

  const bySignature = new Map();
  for (const variant of existing) {
    const sig = signature(usable, variant.optionValues);
    // A manual row without option values must not claim a combination.
    if (sig.split('|').some((part) => part === '')) continue;
    if (!bySignature.has(sig)) bySignature.set(sig, variant);
  }

  const used = new Set();
  const generated = [];
  let added = 0;
  for (const combo of combinations(usable)) {
    const sig = signature(usable, combo.optionValues);
    const found = bySignature.get(sig);
    if (found) {
      used.add(found);
      generated.push({ ...found, orphan: false });
    } else {
      added++;
      generated.push(
        blankVariant({
          name: combo.labels.join(' / '),
          optionValues: combo.optionValues,
          status: 'ACTIVE',
        }),
      );
    }
  }

  const orphans = existing
    .filter((variant) => !used.has(variant))
    .map((v) => ({ ...v, orphan: true }));
  const variants = [...generated, ...orphans].map((variant, position) => ({
    ...variant,
    position,
  }));
  return { variants, added, kept: used.size, orphans: orphans.length };
}

/**
 * True when axes exist and the variant's option values do not form a combination of them
 * (a value removed from an axis, a manual row, a removed axis).
 */
export function isOrphan(axes, variant) {
  const usable = usableAxes(axes);
  if (usable.length === 0) return false;
  return usable.some((axis) => {
    const chosen = variant.optionValues?.[axis.key];
    return chosen === undefined || !axis.values.some((v) => v.key === chosen);
  });
}

/** `variants` with `orphan` recomputed against `axes`. */
export function markOrphans(axes, variants) {
  return variants.map((variant) => ({ ...variant, orphan: isOrphan(axes, variant) }));
}

/** Moves `index` by `delta` (-1 / +1) inside a copy of `list`; out of range = unchanged copy. */
export function moveItem(list, index, delta) {
  const target = index + delta;
  const copy = [...list];
  if (index < 0 || index >= copy.length || target < 0 || target >= copy.length) return copy;
  const [item] = copy.splice(index, 1);
  copy.splice(target, 0, item);
  return copy;
}

/** `position` follows the array order. */
export function renumber(variants) {
  return variants.map((variant, position) => ({ ...variant, position }));
}

/** Copy of a variant as a new unsaved row: no id, no image, own row identity, unlimited-stock rules kept. */
export function duplicateVariant(variant) {
  const { id: _id, ...rest } = variant;
  return {
    ...rest,
    _key: uuid(),
    attributes: (variant.attributes ?? []).map((row) => ({ ...row })),
    prices: (variant.prices ?? []).map((row) => ({ ...row })),
    optionValues: { ...(variant.optionValues ?? {}) },
    imageFile: null,
    imageToken: '',
    imageFileName: null,
    removeImage: false,
  };
}

/** Option values of `variants` with axis `oldKey` renamed to `newKey` (an unsaved axis whose label changed). */
export function renameAxisKey(variants, oldKey, newKey) {
  if (oldKey === newKey) return variants;
  return variants.map((variant) => {
    if (!variant.optionValues || !(oldKey in variant.optionValues)) return variant;
    const { [oldKey]: chosen, ...rest } = variant.optionValues;
    return { ...variant, optionValues: { ...rest, [newKey]: chosen } };
  });
}

/** Option values of `variants` with value `oldKey` of axis `axisKey` renamed to `newKey`. */
export function renameValueKey(variants, axisKey, oldKey, newKey) {
  if (oldKey === newKey) return variants;
  return variants.map((variant) =>
    variant.optionValues?.[axisKey] === oldKey
      ? { ...variant, optionValues: { ...variant.optionValues, [axisKey]: newKey } }
      : variant,
  );
}

/** `name` made unique among `names` (case-insensitive) as `name (2)`, `name (3)`, ... */
export function uniqueName(name, names) {
  const used = new Set(names.map((n) => String(n).trim().toLocaleLowerCase()));
  if (!used.has(String(name).trim().toLocaleLowerCase())) return name;
  let n = 2;
  while (used.has(`${String(name).trim()} (${n})`.toLocaleLowerCase())) n++;
  return `${String(name).trim()} (${n})`;
}
