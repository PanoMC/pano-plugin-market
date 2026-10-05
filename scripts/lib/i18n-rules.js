// Locale rules of 17 §10 `check:i18n`, written against already loaded maps so they can be unit tested.
import { placeholders } from './icu.js';
import { LANGS, PLUGIN_ID } from './common.js';

export const DYNAMIC_PREFIXES = ['enums.', 'errors.', 'schema.errors.'];

// maps: { lang: Map(key -> value) } for one fragment (or the merged set)
export function compareLocales(maps, label) {
  const problems = [];
  const base = maps['en-US'];
  for (const lang of LANGS) {
    const m = maps[lang];
    if (!m) {
      problems.push(`${label}: locale file ${lang}.json missing`);
      continue;
    }
    for (const k of base.keys())
      if (!m.has(k)) problems.push(`${label}: key '${k}' missing in ${lang}`);
    for (const k of m.keys())
      if (!base.has(k)) problems.push(`${label}: key '${k}' in ${lang} but not in en-US`);
    for (const [k, v] of m) {
      // a separator value may be pure whitespace (ru group-separator is U+00A0, 12 §6); never empty
      const blank =
        typeof v === 'string' && (v === '' || (v.trim() === '' && !k.endsWith('-separator')));
      if (typeof v !== 'string' || blank) {
        problems.push(`${label}: empty or non-string value at '${k}' in ${lang}`);
        continue;
      }
      const other = base.get(k);
      if (typeof other === 'string') {
        const a = placeholders(other).join(',');
        const b = placeholders(v).join(',');
        if (a !== b)
          problems.push(
            `${label}: placeholders of '${k}' differ in ${lang} ({${b}} vs en-US {${a}})`,
          );
      }
    }
  }
  return problems;
}

// Literal $_('key') usages -> [{ key, index }]; dynamic (concatenated / templated) ones are skipped.
export function usedKeys(source) {
  const out = [];
  const re = /\$_\(\s*(['"`])((?:\\.|(?!\1).)*)\1\s*([,)+]?)/g;
  let m;
  while ((m = re.exec(source))) {
    const key = m[2];
    if (m[1] === '`' && key.includes('${')) continue;
    if (m[3] === '+') continue;
    out.push({
      key: key.startsWith(`plugins.${PLUGIN_ID}.`)
        ? key.slice(`plugins.${PLUGIN_ID}.`.length)
        : key,
      index: m.index,
    });
  }
  return out;
}

export function keyResolves(key, enUS) {
  if (enUS.has(key)) return true;
  if (DYNAMIC_PREFIXES.some((p) => key.startsWith(p))) return true;
  // a namespace used as a prefix, e.g. $_('pages') is not valid, but 'a.b' with children is a plural/ICU object
  return false;
}
