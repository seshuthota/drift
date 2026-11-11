"""Small residual 1-D CNN CTC encoder for swipe trajectories."""

import torch
import torch.nn as nn

ALPHA = "abcdefghijklmnopqrstuvwxyz"
BLANK = len(ALPHA)
N_CLASSES = len(ALPHA) + 1


class Block(nn.Module):
    def __init__(self, ch, dilation):
        super().__init__()
        self.conv = nn.Conv1d(ch, ch, 3, padding=dilation, dilation=dilation)
        self.bn = nn.BatchNorm1d(ch)
        self.proj = nn.Conv1d(ch, ch, 1)

    def forward(self, x):
        y = torch.nn.functional.gelu(self.bn(self.conv(x)))
        return x + self.proj(y)


class SwipeCtc(nn.Module):
    """[B,2,64] -> log-probs [B,32,27]; blank is the last class."""

    def __init__(self, ch=96, n_blocks=4, in_ch=2):
        super().__init__()
        self.stem = nn.Conv1d(in_ch, ch, 5, stride=2, padding=2)
        self.bn = nn.BatchNorm1d(ch)
        self.blocks = nn.ModuleList(
            Block(ch, d) for d in (1, 2, 4, 8)[:n_blocks]
        )
        self.head = nn.Conv1d(ch, N_CLASSES, 1)

    def forward(self, x):
        x = torch.nn.functional.gelu(self.bn(self.stem(x)))
        for b in self.blocks:
            x = b(x)
        return torch.log_softmax(self.head(x), dim=1).transpose(1, 2)


def greedy_decode(log_probs):
    """[B,T,C] -> list[str]."""
    ids = log_probs.argmax(-1).tolist()
    out = []
    for row in ids:
        prev = -1
        chars = []
        for i in row:
            if i != prev and i != BLANK:
                chars.append(ALPHA[i])
            prev = i
        out.append("".join(chars))
    return out


def pad_targets(words, device="cpu"):
    lengths = torch.tensor([len(w) for w in words], dtype=torch.long)
    maxlen = int(lengths.max()) if len(words) else 0
    targets = torch.zeros((len(words), max(maxlen, 1)), dtype=torch.long)
    for i, w in enumerate(words):
        if w:
            targets[i, : len(w)] = torch.tensor([ALPHA.index(c) for c in w])
    return targets.to(device), lengths.to(device)
