"""Tokenize corpora into uint16 word-id arrays over the app dictionary vocabulary.

Vocabulary = every key of `app/src/main/assets/ime/dict/data.json` (lowercase words, incl.
contractions such as don't / i'm), plus three specials:

    0 <unk>   word outside the dictionary (also anything containing a digit)
    1 <s>     boundary: document start, sentence end (. ! ?), newline
    2 ,       comma, kept as context only (never shown as a prediction)

Word ids are re-ordered by training-corpus frequency (id 3 = most frequent word), so a neural
model can truncate its output layer to the top-K ids.

    python prepare.py --fineweb ../work/nwp/raw/sample/10BT/000_00000.parquet \
        --oasst ../work/nwp/raw/2023-04-12_oasst_ready.messages.jsonl.gz --out ../work/nwp

Larger conversational corpus (the OASST1 test tree bucket is excluded from extras):

    python prepare.py --fineweb ... --oasst ... --oasst-extra ../work/nwp/raw2/oasst2.jsonl.gz \
        --ultrachat ../work/nwp/raw2/train_sft-*.parquet --out ../work/nwp_chat
"""

import argparse
import gzip
import json
import os
import zlib
from multiprocessing import Pool

import numpy as np
import pyarrow.parquet as pq

import tokenizer

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
DICT_PATH = os.path.join(ROOT, "app/src/main/assets/ime/dict/data.json")
UNK, BOS, COMMA = 0, 1, 2

_word_to_id = None


def load_dict_words(path=DICT_PATH):
    return sorted(json.load(open(path, encoding="utf-8")).keys())


def _init(words):
    global _word_to_id
    _word_to_id = {w: i + len(tokenizer.SPECIALS) for i, w in enumerate(words)}


def tokenize(text):
    """Text -> list of ids (dictionary order). Starts with <s>, collapses repeated <s>."""
    return tokenizer.tokenize(text, _word_to_id)


def _tok_batch(texts):
    ids = []
    for t in texts:
        ids.extend(tokenize(t))
    return np.asarray(ids, dtype=np.uint16)


def fineweb_batches(path, batch_docs=2000):
    pf = pq.ParquetFile(path)
    for batch in pf.iter_batches(batch_size=batch_docs, columns=["text"]):
        yield batch.column(0).to_pylist()


def oasst_texts(path):
    """English user prompts, split by conversation tree (90 % train / 10 % test)."""
    train, test = [], []
    with gzip.open(path, "rt", encoding="utf-8") as f:
        for line in f:
            m = json.loads(line)
            if m.get("role") != "prompter" or m.get("lang") != "en":
                continue
            bucket = zlib.crc32(m["message_tree_id"].encode()) % 10
            (test if bucket == 0 else train).append(m["text"])
    return train, test


def oasst_extra_texts(path):
    """English prompts from a second OASST dump, minus the tree bucket used by the OASST1 test split."""
    out = []
    with gzip.open(path, "rt", encoding="utf-8") as f:
        for line in f:
            m = json.loads(line)
            if m.get("role") != "prompter" or m.get("lang") != "en":
                continue
            if zlib.crc32(m["message_tree_id"].encode()) % 10 == 0:
                continue
            out.append(m["text"])
    return out


def ultrachat_texts(paths):
    """User turns of UltraChat conversations (MIT). Prompts and follow-ups are separate samples."""
    texts = []
    for path in paths:
        table = pq.read_table(path, columns=["messages"])
        for row in table.to_pylist():
            for message in row["messages"]:
                if message["role"] == "user":
                    texts.append(message["content"])
    return texts


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--fineweb", required=True)
    ap.add_argument("--oasst", required=True)
    ap.add_argument("--oasst-extra", nargs="*", default=[],
                    help="additional OASST dumps; the tree bucket used by the OASST1 test split is dropped")
    ap.add_argument("--ultrachat", nargs="*", default=[],
                    help="UltraChat parquet files; every user turn becomes a training text")
    ap.add_argument("--out", required=True)
    ap.add_argument("--train-tokens", type=int, default=300_000_000)
    ap.add_argument("--heldout-tokens", type=int, default=2_000_000)
    ap.add_argument("--workers", type=int, default=8)
    args = ap.parse_args()

    words = load_dict_words()
    vocab_size = len(tokenizer.SPECIALS) + len(words)
    assert vocab_size < 65535
    _init(words)

    # Held-out web text comes from the first batches, training from the rest (document-level split).
    web_val, web_test, train_parts = [], [], []
    n_val = n_test = n_train = 0
    with Pool(args.workers, initializer=_init, initargs=(words,)) as pool:
        for arr in pool.imap(_tok_batch, fineweb_batches(args.fineweb)):
            if n_val < args.heldout_tokens:
                web_val.append(arr)
                n_val += len(arr)
            elif n_test < args.heldout_tokens:
                web_test.append(arr)
                n_test += len(arr)
            else:
                train_parts.append(arr)
                n_train += len(arr)
                if len(train_parts) % 50 == 0:
                    print(f"train tokens {n_train / 1e6:.0f}M", flush=True)
                if n_train >= args.train_tokens:
                    pool.terminate()
                    break

    chat_train_txt, chat_test_txt = oasst_texts(args.oasst)
    for path in args.oasst_extra:
        chat_train_txt = chat_train_txt + oasst_extra_texts(path)
    if args.ultrachat:
        chat_train_txt = chat_train_txt + ultrachat_texts(args.ultrachat)
    chat_train = _tok_batch(chat_train_txt)
    chat_test = _tok_batch(chat_test_txt)
    web_train = np.concatenate(train_parts)
    del train_parts

    # Re-order word ids by training frequency (specials keep 0..2).
    counts = np.bincount(web_train, minlength=vocab_size) + np.bincount(chat_train, minlength=vocab_size)
    order = np.argsort(-counts[len(tokenizer.SPECIALS):], kind="stable") + len(tokenizer.SPECIALS)
    new_id = np.empty(vocab_size, dtype=np.uint16)
    new_id[:len(tokenizer.SPECIALS)] = np.arange(len(tokenizer.SPECIALS))
    new_id[order] = np.arange(len(tokenizer.SPECIALS), vocab_size)
    vocab = tokenizer.SPECIALS + [words[i - len(tokenizer.SPECIALS)] for i in order]

    os.makedirs(args.out, exist_ok=True)
    for name, arr in [
        ("web_train", web_train),
        ("web_val", np.concatenate(web_val)),
        ("web_test", np.concatenate(web_test)),
        ("chat_train", chat_train),
        ("chat_test", chat_test),
    ]:
        arr = new_id[arr]
        np.save(os.path.join(args.out, f"{name}.npy"), arr)
        unk = float((arr == UNK).mean())
        print(f"{name}: {len(arr):,} tokens, unk {unk:.2%}", flush=True)
    with open(os.path.join(args.out, "vocab.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(vocab) + "\n")
    total = counts[len(tokenizer.SPECIALS):].sum()
    sorted_counts = np.sort(counts[len(tokenizer.SPECIALS):])[::-1]
    for k in (5_000, 10_000, 20_000, 30_000):
        print(f"top-{k} words cover {sorted_counts[:k].sum() / total:.2%} of in-vocab tokens")
    print(json.dumps({"vocab_size": vocab_size, "chat_train_msgs": len(chat_train_txt),
                      "chat_test_msgs": len(chat_test_txt)}))


if __name__ == "__main__":
    main()
