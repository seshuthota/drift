"""Token rules shared by training (prepare.py) and the Kotlin tokenizer test.

Kept free of third-party imports so `tokenize_golden.py` can run anywhere.
"""

import re

SPECIALS = ["<unk>", "<s>", ","]
UNK, BOS, COMMA = 0, 1, 2
TOKEN_RE = re.compile(r"[a-z0-9]+(?:'[a-z]+)?|[.!?]+|,|\n")


def normalize(text):
    return text.lower().replace("’", "'").replace("‘", "'")


def tokens(text):
    """Text -> token strings. Boundaries collapse to one `<s>`, commas to one `,`."""
    out = []
    for tok in TOKEN_RE.findall(normalize(text)):
        c = tok[0]
        if c == "\n" or c in ".!?":
            if not out or out[-1] != "<s>":
                out.append("<s>")
        elif c == ",":
            if out and out[-1] not in ("<s>", ","):
                out.append(",")
        elif any(ch.isdigit() for ch in tok):
            out.append("<unk>")
        else:
            out.append(tok)
    return out


def tokenize(text, word_to_id):
    """Text -> ids. Starts and ends with `BOS`; a leading boundary is absorbed by the first `BOS`."""
    out = [BOS]
    for tok in tokens(text):
        if tok == "<s>":
            if out[-1] != BOS:
                out.append(BOS)
        elif tok == ",":
            if out[-1] not in (BOS, COMMA):
                out.append(COMMA)
        else:
            out.append(UNK if tok == "<unk>" else word_to_id.get(tok, UNK))
    if out[-1] != BOS:
        out.append(BOS)
    return out
