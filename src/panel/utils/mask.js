// Masks personal data (email, IP) that a panel page shows hidden until the eye button reveals it. Pure.

const DOTS = '•••';

/** `john@example.com` -> `j•••@e•••.com`. A value that is not an email falls back to maskText. */
export function maskEmail(value) {
  const text = String(value ?? '');
  const at = text.lastIndexOf('@');
  if (at <= 0 || at === text.length - 1) return maskText(text);
  const local = text.slice(0, at);
  const domain = text.slice(at + 1);
  const dot = domain.lastIndexOf('.');
  const host = dot > 0 ? domain.slice(0, dot) : domain;
  const tld = dot > 0 ? domain.slice(dot) : '';
  return `${local[0]}${DOTS}@${host[0]}${DOTS}${tld}`;
}

/** IPv4 keeps its first two octets (`192.168.•••.•••`), IPv6 its first two groups. */
export function maskIp(value) {
  const text = String(value ?? '').trim();
  if (/^\d{1,3}(\.\d{1,3}){3}$/.test(text)) {
    const [a, b] = text.split('.');
    return `${a}.${b}.${DOTS}.${DOTS}`;
  }
  if (text.includes(':')) {
    const groups = text.split(':').filter(Boolean);
    if (groups.length >= 2) return `${groups[0]}:${groups[1]}:${DOTS}`;
  }
  return maskText(text);
}

/** Anything else: the first character, then dots. */
export function maskText(value) {
  const text = String(value ?? '');
  return text ? `${text[0]}${DOTS}` : '';
}
