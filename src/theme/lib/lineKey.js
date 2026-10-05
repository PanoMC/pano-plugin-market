// Local cart line identity (14 §6.1). Not the server's SHA-1 lineKey: it only matches lines inside the browser.

const isBlank = (value) => value === '' || value === null || value === undefined;

/** JSON of `fieldValues` with sorted keys and blank ('' / null / undefined) entries removed; `{}` for none. */
export function canonicalFieldValues(fieldValues) {
  const out = {};

  if (fieldValues && typeof fieldValues === 'object' && !Array.isArray(fieldValues)) {
    for (const key of Object.keys(fieldValues).sort()) {
      if (!isBlank(fieldValues[key])) out[key] = fieldValues[key];
    }
  }

  return JSON.stringify(out);
}

/** `"<productId>|<variantId||0>|<canonical(fieldValues)>|<targetServerId||''>"`. */
export function lineKey(line) {
  const l = line || {};

  return `${l.productId}|${l.variantId || 0}|${canonicalFieldValues(l.fieldValues)}|${l.targetServerId || ''}`;
}
