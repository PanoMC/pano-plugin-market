// ISO 3166-1 alpha-2 country codes for the billing country select (14 §10.5). Pure: no SDK, no DOM.
// Labels are produced by the caller (`countryName(code)` of the market/format controller); this module only holds the
// codes and sorts / filters them.

export const COUNTRY_CODES = Object.freeze(
  (
    'AD AE AF AG AI AL AM AO AQ AR AS AT AU AW AX AZ BA BB BD BE BF BG BH BI BJ BL BM BN BO BQ BR BS BT BV BW BY BZ ' +
    'CA CC CD CF CG CH CI CK CL CM CN CO CR CU CV CW CX CY CZ DE DJ DK DM DO DZ EC EE EG EH ER ES ET FI FJ FK FM FO ' +
    'FR GA GB GD GE GF GG GH GI GL GM GN GP GQ GR GS GT GU GW GY HK HM HN HR HT HU ID IE IL IM IN IO IQ IR IS IT JE ' +
    'JM JO JP KE KG KH KI KM KN KP KR KW KY KZ LA LB LC LI LK LR LS LT LU LV LY MA MC MD ME MF MG MH MK ML MM MN MO ' +
    'MP MQ MR MS MT MU MV MW MX MY MZ NA NC NE NF NG NI NL NO NP NR NU NZ OM PA PE PF PG PH PK PL PM PN PR PS PT PW ' +
    'PY QA RE RO RS RU RW SA SB SC SD SE SG SH SI SJ SK SL SM SN SO SR SS ST SV SX SY SZ TC TD TF TG TH TJ TK TL TM ' +
    'TN TO TR TT TV TW TZ UA UG UM US UY UZ VA VC VE VG VI VN VU WF WS YE YT ZA ZM ZW'
  ).split(' '),
);

const KNOWN = new Set(COUNTRY_CODES);

/** True when `code` is an upper-case ISO alpha-2 code of the list. */
export const isCountryCode = (code) => typeof code === 'string' && KNOWN.has(code);

/**
 * `[{ code, label }]` of `codes` (default: every country), sorted by label with the locale of the labels.
 * Unknown / duplicate / non-string entries are dropped; codes are upper-cased. `labelOf(code)` gives the label.
 */
export function countryOptions(codes = COUNTRY_CODES, labelOf = (code) => code, locale) {
  const seen = new Set();
  const out = [];

  for (const raw of Array.isArray(codes) ? codes : []) {
    const code = typeof raw === 'string' ? raw.trim().toUpperCase() : '';
    if (!code || seen.has(code)) continue;
    seen.add(code);
    out.push({ code, label: String(labelOf(code) || code) });
  }

  let collator;
  try {
    collator = new Intl.Collator(locale);
  } catch (e) {
    collator = new Intl.Collator();
  }

  return out.sort((a, b) => collator.compare(a.label, b.label) || a.code.localeCompare(b.code));
}
