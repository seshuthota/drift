"""Export the LSTM next-word model to ONNX (fp32 + dynamic int8) and check accuracy/latency.

    python export_onnx.py --ckpt ../work/nwp/lstm_v20k/best.pt --data ../work/nwp --out ../work/nwp/nwp_lstm

Input  `ids`    int64 [1, T]   frequency-ordered word ids (>= vocab mapped to 0 by the caller)
Output `logits` float [1, V]   scores for the word following the last id

Writes <out>.onnx, <out>.int8.onnx and <out>.vocab.txt (the first V lines of vocab.txt).
"""

import argparse
import os
import time

import numpy as np
import onnxruntime as ort
import torch
from onnxruntime.quantization import QuantType, quantize_dynamic

from lstm import N_SPECIAL, TinyLstm, sample_positions


class LastLogits(torch.nn.Module):
    """Export view of TinyLstm: one shared [emb, V] table feeds both the lookup and the output layer,
    so the tied embedding is stored (and quantized) once."""

    def __init__(self, model):
        super().__init__()
        self.table = torch.nn.Parameter(model.emb.weight.detach().t().contiguous())  # [E, V]
        self.rnn = model.rnn
        self.proj = model.proj
        self.out_bias = model.out_bias

    def forward(self, ids):
        e = torch.index_select(self.table, 1, ids[0]).t().unsqueeze(0)  # [1, T, E]
        x, _ = self.rnn(e)
        h = self.proj(x[:, -1])
        return torch.matmul(h, self.table) + self.out_bias


def ort_accuracy(sess, tokens_full, vocab, positions, context=16):
    idx = sample_positions(tokens_full, positions)
    toks = tokens_full.astype(np.int64)
    toks_in = toks.copy()
    toks_in[toks_in >= vocab] = 0
    hits = np.zeros(3)
    for i in idx:
        end = i + 2
        win = toks_in[max(0, end - context):end][None]
        logits = sess.run(None, {"ids": win})[0][0]
        logits[:N_SPECIAL] = -1e9
        top = np.argpartition(-logits, 5)[:5]
        top = top[np.argsort(-logits[top])]
        t = toks[end]
        hits += [t == top[0], t in top[:3], t in top[:5]]
    return hits / len(idx)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ckpt", required=True)
    ap.add_argument("--data", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--positions", type=int, default=5000)
    args = ap.parse_args()

    ck = torch.load(args.ckpt, map_location="cpu")
    a = ck["args"]
    model = TinyLstm(a["vocab"], a["emb"], a["hidden"], a["layers"], 0.0)
    model.load_state_dict(ck["model"])
    model.eval()
    wrapped = LastLogits(model)

    fp32 = args.out + ".onnx"
    int8 = args.out + ".int8.onnx"
    torch.onnx.export(
        wrapped, torch.zeros(1, 16, dtype=torch.long), fp32,
        input_names=["ids"], output_names=["logits"],
        dynamic_axes={"ids": {1: "T"}}, opset_version=17, dynamo=False,
    )
    quantize_dynamic(fp32, int8, weight_type=QuantType.QInt8, per_channel=False,
                     op_types_to_quantize=["MatMul", "Gemm", "Gather", "LSTM"])
    vocab = open(os.path.join(args.data, "vocab.txt"), encoding="utf-8").read().split("\n")[: a["vocab"]]
    with open(args.out + ".vocab.txt", "w", encoding="utf-8") as f:
        f.write("\n".join(vocab) + "\n")

    for path in (fp32, int8):
        opts = ort.SessionOptions()
        opts.intra_op_num_threads = 1
        sess = ort.InferenceSession(path, opts, providers=["CPUExecutionProvider"])
        x = np.random.randint(3, a["vocab"], size=(1, 16)).astype(np.int64)
        for _ in range(20):
            sess.run(None, {"ids": x})
        t0 = time.perf_counter()
        for _ in range(200):
            sess.run(None, {"ids": x})
        ms = (time.perf_counter() - t0) / 200 * 1000
        size = os.path.getsize(path) / 1e6
        print(f"{os.path.basename(path)}: {size:.2f} MB, {ms:.2f} ms/call (T=16, 1 thread)", flush=True)
        for name in ("web_test", "chat_test"):
            full = np.load(os.path.join(args.data, f"{name}.npy"))
            t1, t3, t5 = ort_accuracy(sess, full, a["vocab"], args.positions)
            print(f"  {name}: top1={t1:.2%} top3={t3:.2%} top5={t5:.2%}", flush=True)


if __name__ == "__main__":
    main()
