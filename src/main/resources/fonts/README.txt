Noto Sans 2.015 Regular and Bold (SIL Open Font License 1.1, see OFL.txt), subset to what an invoice can print:
Basic Latin, Latin-1, Latin Extended-A/B and Additional (Turkish, Vietnamese, ...), Greek, Cyrillic incl. the Cyrillic supplement,
punctuation, super/subscripts, currency signs (lira, ruble, euro, pound, hryvnia, tenge, manat, rupee, ...), letterlike symbols and a few
arrows / math signs. Anything else (CJK, Arabic, emoji, ...) prints as "?" (12-mail-invoice.md section 8.2).

Made from the upstream files with:
  pyftsubset NotoSans-<Weight>.ttf --glyph-names --notdef-outline --layout-features='kern,liga,ccmp,locl,mark,mkmk' \
    --unicodes='U+0020-007E,U+00A0-024F,U+0259,U+0300-036F,U+0370-03FF,U+0400-052F,U+1E00-1EFF,U+2000-206F,U+2070-209F,U+20A0-20CF,U+2100-214F,U+2190-2193,U+2212,U+2215,U+2260,U+2264,U+2265,U+FFFD'
The full upstream fonts are 620 KB each; the subset keeps the market jar inside its size budget (16 section 6.2).
