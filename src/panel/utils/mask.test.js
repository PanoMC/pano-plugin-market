import { describe, expect, test } from 'bun:test';
import { maskEmail, maskIp, maskText } from './mask.js';

describe('maskEmail', () => {
  test('keeps the first letters and the TLD', () => {
    expect(maskEmail('john@example.com')).toBe('j•••@e•••.com');
    expect(maskEmail('a@mail.co.uk')).toBe('a•••@m•••.uk');
  });
  test('a domain without a dot', () => {
    expect(maskEmail('root@localhost')).toBe('r•••@l•••');
  });
  test('not an email', () => {
    expect(maskEmail('nope')).toBe('n•••');
    expect(maskEmail('@x.com')).toBe('@•••');
    expect(maskEmail('')).toBe('');
    expect(maskEmail(null)).toBe('');
  });
});

describe('maskIp', () => {
  test('IPv4 keeps two octets', () => {
    expect(maskIp('192.168.1.42')).toBe('192.168.•••.•••');
  });
  test('IPv6 keeps two groups', () => {
    expect(maskIp('2001:db8::1')).toBe('2001:db8:•••');
  });
  test('anything else', () => {
    expect(maskIp('unknown')).toBe('u•••');
    expect(maskText('')).toBe('');
  });
});
