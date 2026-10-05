import { describe, expect, test } from 'bun:test';
import {
  BOM,
  CREATOR_REPORT_COLUMNS,
  creatorReportRows,
  creatorsFilename,
  guardCell,
  toCsv,
} from './csv.js';

describe('csv', () => {
  test('21: quotes quote, delimiter, CR and LF; BOM; CRLF line ends', () => {
    const text = toCsv(
      [
        { a: 'say "hi"', b: 'x,y' },
        { a: 'line1\nline2', b: 'cr\rend' },
        { a: 'plain', b: '' },
      ],
      ['a', 'b'],
    );
    expect(text.startsWith(BOM)).toBe(true);
    expect(text.charCodeAt(0)).toBe(0xfeff);
    const body = text.slice(1);
    expect(body).toBe(
      'a,b\r\n"say ""hi""","x,y"\r\n"line1\nline2","cr\rend"\r\nplain,\r\n',
    );
    expect(body.endsWith('\r\n')).toBe(true);
  });

  test('21: header row uses the column header, missing keys are empty', () => {
    const text = toCsv([{ a: 1 }], [{ key: 'a', header: 'A' }, 'b']);
    expect(text).toBe(BOM + 'A,b\r\n1,\r\n');
  });

  test('22: cells starting with = + - @ TAB CR are prefixed with a quote', () => {
    for (const lead of ['=', '+', '-', '@', '\t']) {
      expect(guardCell(`${lead}SUM(A1)`)).toBe(`'${lead}SUM(A1)`);
    }
    expect(guardCell('\rboom')).toBe("'\rboom");
    expect(guardCell('safe=1')).toBe('safe=1');
    const text = toCsv([{ a: '=1+1', b: '@x' }], ['a', 'b']);
    expect(text).toBe(BOM + "a,b\r\n'=1+1,'@x\r\n");
  });

  test('22: a prefixed cell that also holds the delimiter is quoted after the guard', () => {
    expect(toCsv([{ a: '=a,b' }], ['a'])).toBe(BOM + 'a\r\n"\'=a,b"\r\n');
  });

  test('22: real negative numbers stay numbers, negative text is guarded', () => {
    expect(toCsv([{ a: -5, b: '-5' }], ['a', 'b'])).toBe(BOM + "a,b\r\n-5,'-5\r\n");
  });

  test('23: semicolon delimiter', () => {
    const text = toCsv([{ a: 'x;y', b: 'p,q' }], ['a', 'b'], ';');
    expect(text).toBe(BOM + 'a;b\r\n"x;y";p,q\r\n');
  });

  test('creator report: columns, currency column and file name', () => {
    const rows = creatorReportRows(
      [{ id: 1, creator: 'Alex', code: 'ALEX', uses: 3, revenue: 30, earned: 3, paidOut: 1, available: 2 }],
      'USD',
    );
    const text = toCsv(rows, CREATOR_REPORT_COLUMNS);
    expect(text).toBe(
      BOM +
        'creator,code,uses,revenue,earned,paidOut,available,currency\r\nAlex,ALEX,3,30,3,1,2,USD\r\n',
    );
    expect(creatorsFilename(new Date(2026, 9, 5))).toBe('creators-2026-10-05.csv');
  });
});
