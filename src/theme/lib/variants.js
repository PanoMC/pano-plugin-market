// Variant selection of the product page (14 §9.2, VariantPicker). Pure: no SDK, no DOM.
//
// variantOptions: [{ key, label, values: [{ key, label }] }]   (axes)
// variants:       [{ id, name, optionValues: { <axisKey>: <valueKey> }, price, inStock, ... }]
// selection:      { <axisKey>: <valueKey> }

const same = (a, b) => String(a) === String(b);

/** Axes that carry at least one value; a variant list without usable axes uses the plain select. */
export function usableAxes(variantOptions) {
  return (Array.isArray(variantOptions) ? variantOptions : []).filter(
    (axis) => axis && axis.key != null && Array.isArray(axis.values) && axis.values.length > 0,
  );
}

/** True when the product is picked with axis buttons (otherwise one select of variant names). */
export function hasAxes(variantOptions) {
  return usableAxes(variantOptions).length > 0;
}

/**
 * The variant whose `optionValues` equals `selection` on every axis; null when the selection is
 * incomplete (an axis without a value) or no variant has that combination.
 */
export function resolve(variants, selection, axes) {
  const list = Array.isArray(variants) ? variants : [];
  const keys = (Array.isArray(axes) ? axes : usableAxes(axes)).map((axis) => axis.key);
  const wanted = selection && typeof selection === 'object' ? selection : {};

  if (!keys.length) return null;
  if (keys.some((key) => wanted[key] == null || wanted[key] === '')) return null;

  return (
    list.find((variant) =>
      keys.every((key) => variant?.optionValues && same(variant.optionValues[key], wanted[key])),
    ) ?? null
  );
}

/**
 * A value is selectable when some variant has it and matches the current selection on all OTHER axes
 * (axes without a selection do not restrict). A sold-out variant stays selectable.
 */
export function isValueSelectable(variants, axes, selection, axisKey, valueKey) {
  const list = Array.isArray(variants) ? variants : [];
  const wanted = selection && typeof selection === 'object' ? selection : {};
  const others = (Array.isArray(axes) ? axes : usableAxes(axes))
    .map((axis) => axis.key)
    .filter((key) => !same(key, axisKey));

  return list.some((variant) => {
    const values = variant?.optionValues || {};
    if (values[axisKey] == null || !same(values[axisKey], valueKey)) return false;

    return others.every(
      (key) => wanted[key] == null || wanted[key] === '' || same(values[key], wanted[key]),
    );
  });
}

/** `{ axisKey: valueKey }` of one variant, restricted to the known axes. */
export function selectionOf(variant, axes) {
  const out = {};

  for (const axis of Array.isArray(axes) ? axes : usableAxes(axes)) {
    const value = variant?.optionValues?.[axis.key];
    if (value != null) out[axis.key] = String(value);
  }

  return out;
}

/** Positive integer from `?variant=`, else null. */
export function parseVariantParam(value) {
  const text = typeof value === 'string' ? value.trim() : '';
  if (!/^\d{1,12}$/.test(text)) return null;

  const id = Number(text);

  return id > 0 ? id : null;
}

/**
 * The variant that is selected first: the one of `?variant=<id>` when it is a listed variant, else the
 * first variant with `inStock`, else the first variant; null without variants.
 */
export function initialVariant(variants, urlId) {
  const list = Array.isArray(variants) ? variants : [];
  if (!list.length) return null;

  const wanted = parseVariantParam(urlId == null ? null : String(urlId));
  const listed = wanted == null ? null : list.find((variant) => Number(variant?.id) === wanted);

  return listed ?? list.find((variant) => variant?.inStock) ?? list[0];
}

/** { variant, selection } for the first render. */
export function initialSelection(variants, variantOptions, urlId) {
  const variant = initialVariant(variants, urlId);

  return { variant, selection: variant ? selectionOf(variant, variantOptions) : {} };
}

/**
 * Selection after the buyer clicks `valueKey` on `axisKey`. When the new combination does not exist, the
 * other axes are moved to the first variant that has the clicked value (so a click never leaves a dead end).
 */
export function selectValue(variants, axes, selection, axisKey, valueKey) {
  const next = { ...(selection || {}), [axisKey]: String(valueKey) };
  if (resolve(variants, next, axes)) return next;

  const list = Array.isArray(variants) ? variants : [];
  const match = list.find(
    (variant) => variant?.optionValues && same(variant.optionValues[axisKey], valueKey),
  );

  return match ? { ...selectionOf(match, axes), [axisKey]: String(valueKey) } : next;
}

/**
 * The product as the page shows it for the resolved variant: price, compareAtPrice, creditPrice, inStock,
 * stock, image and the period count come from the variant. Without a variant the product is returned as is.
 */
export function effectiveProduct(product, variant) {
  if (!variant) return product;

  const merged = { ...product, priceFrom: false };
  for (const key of ['price', 'compareAtPrice', 'creditPrice', 'inStock', 'stock']) {
    if (key in variant) merged[key] = variant[key];
  }
  if (variant.imageFileName) merged.imageFileName = variant.imageFileName;
  if (variant.periodCount != null && product?.period)
    merged.period = { ...product.period, count: variant.periodCount };

  return merged;
}
