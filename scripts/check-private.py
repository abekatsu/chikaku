#!/usr/bin/env python3
"""書こうとしている内容に、公開してはいけない値が混ざっていないか調べる。

**なぜ要るか。** 2026-10 に、テストのフィクスチャへ本番の座標を小数第6位まで
書いたまま公開寸前までいった。悪意でも不注意でもなく、「テストに意味を持たせる
には実データが良い」という真っ当な判断の結果だった。だから判断に任せず、
機械で止める。

二段構えにしてある。

- **既知の実値** (`.private-patterns` に列挙) に当たったら **止める**。
  値が分かっているので誤検知しない。
- **形による検出**（小数5桁以上の座標、example 系でないメールアドレス、
  秘密鍵のヘッダなど）は **警告だけ**。一覧に無い新しい実データを拾える代わりに
  誤検知するので、止めると邪魔になる。

`.private-patterns` 自体が機微な値の一覧なので gitignore してある。
clone しても付いてこない。新しい環境では作り直すこと
（雛形: `.private-patterns.example`）。

使い方:
    check-private.py --staged          # commit されようとしている差分
    check-private.py --hook            # Claude Code の PreToolUse から stdin 経由
    check-private.py FILE [FILE...]    # 指定ファイル

終了コード: 0 = 問題なし / 2 = 既知の値に当たった（呼び出し側は中止する）
"""

from __future__ import annotations

import json
import os
import re
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
PATTERNS_FILE = REPO / ".private-patterns"

# 検査しても意味がないもの。ロックファイルは長い hex の塊で、
# 形による検出がまず誤爆する。
SKIP = re.compile(
    r"(^|/)(node_modules|\.git|build|dist|target|\.gradle|DerivedData)/"
    r"|(package-lock\.json|Cargo\.lock|\.lock)$"
    r"|\.(png|jpe?g|gif|webp|ico|pdf|zip|jar|apk|aab|keystore|jks|wasm)$"
)

# 形による検出。止めずに警告するだけなので、多少広くても構わない。
SHAPE = [
    (
        "座標らしき値（小数5桁以上）",
        re.compile(r"-?\d{1,3}\.\d{5,}\s*,\s*-?\d{1,3}\.\d{5,}"),
    ),
    (
        "座標らしき値（lat/lng の近く）",
        re.compile(r"(?i)\b(lat|lng|latitude|longitude)\b\D{0,12}-?\d{1,3}\.\d{5,}"),
    ),
    (
        "実在しそうなメールアドレス",
        re.compile(
            r"\b[A-Za-z0-9._%+-]+@(?!example\.|test\.|invalid\b|localhost)"
            r"[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b"
        ),
    ),
    ("秘密鍵", re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----")),
    ("AWS のアクセスキー", re.compile(r"\bAKIA[0-9A-Z]{16}\b")),
    ("GitHub のトークン", re.compile(r"\bgh[pousr]_[A-Za-z0-9]{30,}")),
    ("JWT", re.compile(r"\beyJ[A-Za-z0-9_-]{10,}\.eyJ[A-Za-z0-9_-]{10,}\.")),
]


def load_known() -> list[tuple[str, re.Pattern[str]]]:
    """`.private-patterns` を読む。1 行 1 正規表現、`#` はコメント。"""
    if not PATTERNS_FILE.exists():
        return []
    out = []
    for raw in PATTERNS_FILE.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        label, _, pat = line.partition("\t")
        if not pat:
            label, pat = "既知の値", line
        try:
            out.append((label, re.compile(pat)))
        except re.error as e:
            print(f"  .private-patterns の正規表現が不正: {line!r} ({e})", file=sys.stderr)
    return out


def redact(s: str) -> str:
    """見つけた値そのものはログに出さない。出したら意味がない。"""
    return s[:3] + "…" + s[-2:] if len(s) > 6 else "…"


# 架空データであることを宣言する印。これが書いてあるファイルでは形による警告を
# 出さない。**既知の実値の照合は止めない** -- そちらは誤検知しないので、黙らせる
# 理由がない。
#
# 警告を黙らせる手段を用意するのは、鳴りっぱなしの警告は読まれなくなるから。
# 代わりに宣言をファイルに残させることで、レビューで見える形にしている。
SYNTHETIC_MARKER = re.compile(r"check-private:\s*synthetic")


def scan(name: str, text: str) -> tuple[list[str], list[str]]:
    blocked, warned = [], []
    for label, pat in load_known():
        m = pat.search(text)
        if m:
            blocked.append(f"{name}: {label} ({redact(m.group(0))})")
    if not SYNTHETIC_MARKER.search(text):
        for label, pat in SHAPE:
            m = pat.search(text)
            if m:
                warned.append(f"{name}: {label} ({redact(m.group(0))})")
    return blocked, warned


def staged() -> list[tuple[str, str]]:
    files = subprocess.run(
        ["git", "diff", "--cached", "--name-only", "--diff-filter=ACM"],
        capture_output=True, text=True, cwd=REPO,
    ).stdout.split()
    out = []
    for f in files:
        if SKIP.search(f):
            continue
        blob = subprocess.run(
            ["git", "show", f":{f}"], capture_output=True, text=True, cwd=REPO
        )
        if blob.returncode == 0:
            out.append((f, blob.stdout))
    return out


def from_hook() -> list[tuple[str, str]]:
    """Claude Code の PreToolUse hook。tool_input から書き込む中身を取る。"""
    try:
        ev = json.load(sys.stdin)
    except Exception:
        return []
    ti = ev.get("tool_input") or {}
    path = ti.get("file_path") or ti.get("notebook_path") or "(入力)"
    if SKIP.search(str(path)):
        return []
    # Write は content、Edit は new_string、Bash はコマンド本体。
    parts = [ti.get(k) for k in ("content", "new_string", "command")]
    text = "\n".join(p for p in parts if isinstance(p, str))
    return [(str(path), text)] if text else []


def main() -> int:
    arg = sys.argv[1] if len(sys.argv) > 1 else "--staged"
    if arg == "--staged":
        items = staged()
    elif arg == "--hook":
        items = from_hook()
    else:
        items = [
            (f, Path(f).read_text(encoding="utf-8", errors="replace"))
            for f in sys.argv[1:]
            if not SKIP.search(f) and Path(f).is_file()
        ]

    blocked, warned = [], []
    for name, text in items:
        b, w = scan(name, text)
        blocked += b
        warned += w

    if warned:
        print("⚠ 実データかもしれない値（止めてはいない。目で見て判断すること）", file=sys.stderr)
        for w in dict.fromkeys(warned):
            print(f"    {w}", file=sys.stderr)

    if blocked:
        print("", file=sys.stderr)
        print("✗ 公開できない既知の値が含まれている", file=sys.stderr)
        for b in dict.fromkeys(blocked):
            print(f"    {b}", file=sys.stderr)
        print("", file=sys.stderr)
        print("  テストや例に実データを使わない。距離や桁数など、", file=sys.stderr)
        print("  検証に効いている性質だけ保って架空の値に置き換える。", file=sys.stderr)
        print("  一覧は .private-patterns（gitignore 済み）。", file=sys.stderr)
        return 2

    return 0


if __name__ == "__main__":
    sys.exit(main())
