// Placeholder extraction for ICU-style messages: {name}, {count, plural, one {# x} other {...}}.
export function placeholders(message) {
  const names = new Set();
  parse(String(message), 0, names, false);
  return [...names].sort();
}

function parse(s, start, names, inBranch) {
  let i = start;
  while (i < s.length) {
    const c = s[i];
    if (c === '{') {
      const end = matching(s, i);
      const inner = s.slice(i + 1, end);
      const m = /^\s*([A-Za-z_][\w-]*)\s*(?:,\s*(plural|select|selectordinal)\s*,)?/.exec(inner);
      if (m) {
        names.add(m[1]);
        if (m[2]) {
          // branches: key {text} ...
          let j = m[0].length;
          while (j < inner.length) {
            const open = inner.indexOf('{', j);
            if (open === -1) break;
            const close = matching(inner, open);
            parse(inner.slice(open + 1, close), 0, names, true);
            j = close + 1;
          }
        }
      }
      i = end + 1;
    } else i++;
  }
}

function matching(s, open) {
  let depth = 0;
  for (let i = open; i < s.length; i++) {
    if (s[i] === '{') depth++;
    else if (s[i] === '}' && --depth === 0) return i;
  }
  return s.length - 1;
}
