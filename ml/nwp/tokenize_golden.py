"""Regenerate the tokenizer golden file read by NwpTokenizerGoldenTest.

    python ml/nwp/tokenize_golden.py

The Kotlin tokenizer (NwpText.tokens) and this module must agree; both are
checked against the same file, so a change on either side fails the test.
"""

import json
import os

import tokenizer

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "..", "app/src/test/resources/nwp/tokenize_golden.json")

CASES = [
    "",
    " ",
    "\n",
    ".",
    "...",
    "hello",
    "Hello, world!",
    "hi. there",
    "one!two?three.",
    "a\n\nb",
    ", leading comma",
    "a, , b",
    "a,,b",
    "x.y.z",
    "don\u2019t stop",
    "don't stop",
    "I\u2019m here",
    "abc123 def",
    "Caf\u00e9",
    "na\u00efve",
    "emoji \U0001F600 here",
    "tab\there",
    "  leading spaces and. trailing  ",
    "a-b_c",
    "THE END.",
    "under_score 42",
]


def main():
    golden = [{"in": text, "tokens": tokenizer.tokens(text)} for text in CASES]
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as handle:
        json.dump(golden, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    print(f"wrote {len(golden)} cases to {os.path.relpath(OUT)}")


if __name__ == "__main__":
    main()
