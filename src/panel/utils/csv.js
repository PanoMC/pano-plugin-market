// Client-side CSV of the creator report (13 §12.4). Pure apart from downloadCsv (browser only).

export const BOM = '﻿';
const FORMULA_START = /^[=+\-@\t\r]/;

/** A cell starting with = + - @ TAB or CR is prefixed with ' so a spreadsheet never runs it. */
export function guardCell(value) {
  const text = value === null || value === undefined ? '' : String(value);
  return FORMULA_START.test(text) ? `'${text}` : text;
}

/** RFC 4180 quoting: fields holding the delimiter, a quote, CR or LF are quoted, quotes doubled. */
export function quoteCell(text, delimiter = ',') {
  if (text.includes('"') || text.includes(delimiter) || /[\r\n]/.test(text))
    return `"${text.replaceAll('"', '""')}"`;
  return text;
}

/**
 * `rows` = array of objects; `columns` = keys (or `{ key, header }`); `delimiter` = `,` or `;` (`tab`
 * is accepted for a TAB). The first line is the header. CRLF line ends, UTF-8 BOM prefix.
 * A numeric cell is written as is (a negative number is not a formula).
 */
export function toCsv(rows, columns, delimiter = ',') {
  const sep = delimiter === 'tab' ? '\t' : delimiter;
  const cols = columns.map((c) => (typeof c === 'string' ? { key: c, header: c } : c));
  const cell = (value) => {
    if (typeof value === 'number' && Number.isFinite(value))
      return quoteCell(String(value), sep);
    return quoteCell(guardCell(value), sep);
  };
  const lines = [cols.map((c) => cell(c.header ?? c.key)).join(sep)];
  for (const row of rows ?? []) lines.push(cols.map((c) => cell(row?.[c.key])).join(sep));
  return BOM + lines.join('\r\n') + '\r\n';
}

/** Browser only: Blob + object URL + a temporary `<a download>`. */
export function downloadCsv(filename, text) {
  const blob = new Blob([text], { type: 'text/csv;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  a.rel = 'noopener';
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

/** `creators-<yyyy-mm-dd>.csv` for a Date (browser zone). */
export function creatorsFilename(date = new Date()) {
  const two = (n) => String(n).padStart(2, '0');
  return `creators-${date.getFullYear()}-${two(date.getMonth() + 1)}-${two(date.getDate())}.csv`;
}

/** Columns of the creator report export (13 §12.4). */
export const CREATOR_REPORT_COLUMNS = [
  'creator',
  'code',
  'uses',
  'revenue',
  'earned',
  'paidOut',
  'available',
  'currency',
];

/** Report rows plus the report currency, ready for toCsv(rows, CREATOR_REPORT_COLUMNS). */
export function creatorReportRows(creators, currency) {
  return (creators ?? []).map((row) => ({ ...row, currency: currency ?? '' }));
}
