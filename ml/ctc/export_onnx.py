"""Export a checkpoint to ONNX and check Torch/ONNX parity."""

import argparse
import os

import numpy as np
import torch

from model import SwipeCtc


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ckpt", required=True)
    ap.add_argument("--out", default="ctc_swipe.onnx")
    ap.add_argument("--npz", default="work/test_rand.npz")
    ap.add_argument("--rows", type=int, default=32)
    args = ap.parse_args()

    ck = torch.load(args.ckpt, map_location="cpu")
    model = SwipeCtc(ch=ck["args"]["ch"])
    model.load_state_dict(ck["model"])
    model.eval()

    dummy = torch.randn(1, 2, 64)
    torch.onnx.export(
        model,
        dummy,
        args.out,
        input_names=["features"],
        output_names=["log_probs"],
        dynamic_axes={"features": {0: "batch"}, "log_probs": {0: "batch"}},
        opset_version=17,
        dynamo=False,
    )
    size_mb = os.path.getsize(args.out) / 1e6
    print(f"exported {args.out} ({size_mb:.2f} MB)", flush=True)

    import onnxruntime as ort

    d = np.load(args.npz, allow_pickle=True)
    x = d["feats"][: args.rows].astype(np.float32)
    sess = ort.InferenceSession(args.out, providers=["CPUExecutionProvider"])
    onnx_out = sess.run(None, {"features": x})[0]
    with torch.no_grad():
        torch_out = model(torch.from_numpy(x)).numpy()
    print(
        f"max_abs_diff={np.abs(onnx_out - torch_out).max():.2e} "
        f"argmax_match={(onnx_out.argmax(-1) == torch_out.argmax(-1)).mean():.4f}",
        flush=True,
    )


if __name__ == "__main__":
    main()
