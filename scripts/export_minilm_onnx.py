"""Export all-MiniLM-L6-v2 to ONNX for the Android app's query embedder.

Why this exists
---------------
The corpus vectors in brain.db were produced by sentence-transformers/
all-MiniLM-L6-v2 (ingestion/embed.py). A query vector compared against them
must come from the SAME model, or cosine similarity is noise dressed up as a
ranking. That rules out MediaPipe TextEmbedder (Universal Sentence Encoder,
100d, a different space) and any "generic" on-device text embedder: the only
correct options are these exact weights, or no vector arm at all.

The export is verified here rather than trusted. A wrong pooling strategy or a
tokenizer mismatch does not raise -- it produces vectors, and rankings, that are
simply wrong. So this script embeds sample sentences with BOTH sentence-
transformers and the exported graph and refuses to write the model unless they
agree to 1e-4.

fp32 (~86MB) is deliberate. int8 dynamic quantisation gets to ~23MB but shifts
the vectors, and the entire value of the vector arm is agreeing with the corpus
it scores against. Ship the big one; the APK is sideloaded.

Usage:
    python scripts/export_minilm_onnx.py
    python scripts/export_minilm_onnx.py --out android-app/app/src/main/assets/minilm
"""
from __future__ import annotations

import argparse
import shutil
import sys
from pathlib import Path

import numpy as np
import torch

PROJECT_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PROJECT_ROOT))

MODEL_ID = "sentence-transformers/all-MiniLM-L6-v2"
MAX_LEN = 256

# Sentences chosen to exercise what the tokenizer actually meets in this corpus:
# ordinary prose, an all-caps acronym, digits and punctuation, a subject code
# that will certainly split into word pieces, and an out-of-vocabulary surname.
PROBES = [
    "What is the minimum attendance percentage?",
    "KRIET is affiliated to DBATU, Lonere.",
    "Fee payment deadline: 15 August 2026 (late fee Rs. 500/-).",
    "Subject BTCOL506 carries 2 credits.",
    "Marksheet of Hajare Nikhil Rajendra",
    "scholarship eligibility for SC/ST students",
]


def mean_pool(last_hidden_state: np.ndarray, attention_mask: np.ndarray) -> np.ndarray:
    """Mean over non-padding positions.

    This is what sentence-transformers does for this model. Taking the CLS
    token instead is the classic mistake: it runs, it returns a 384d vector,
    and every similarity it produces is subtly wrong.
    """
    mask = attention_mask[..., None].astype(np.float32)
    summed = (last_hidden_state * mask).sum(axis=1)
    counts = np.clip(mask.sum(axis=1), 1e-9, None)
    return summed / counts


def l2_normalize(x: np.ndarray) -> np.ndarray:
    return x / np.clip(np.linalg.norm(x, axis=-1, keepdims=True), 1e-12, None)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", default="android-app/app/src/main/assets/minilm",
                    help="directory to write model.onnx and vocab.txt into")
    ap.add_argument("--tolerance", type=float, default=1e-4)
    args = ap.parse_args(argv)

    out_dir = (PROJECT_ROOT / args.out) if not Path(args.out).is_absolute() else Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    tmp_model = out_dir / "model.onnx.tmp"
    final_model = out_dir / "model.onnx"

    from transformers import AutoModel, AutoTokenizer

    print(f"loading {MODEL_ID} ...")
    tokenizer = AutoTokenizer.from_pretrained(MODEL_ID)
    model = AutoModel.from_pretrained(MODEL_ID).eval()

    # --- export -------------------------------------------------------------
    # Exported through an explicit wrapper rather than by handing BertModel a
    # positional tuple. transformers 5.x accepts extra keyword-only arguments on
    # forward(), so a positional tuple binds to the wrong parameters and the
    # export dies with "got multiple values for argument 'use_cache'". Naming
    # the three inputs here also pins the graph's input order, which the Kotlin
    # side depends on.
    class Wrapped(torch.nn.Module):
        def __init__(self, inner):
            super().__init__()
            self.inner = inner

        def forward(self, input_ids, attention_mask, token_type_ids):
            return self.inner(
                input_ids=input_ids,
                attention_mask=attention_mask,
                token_type_ids=token_type_ids,
            ).last_hidden_state

    wrapped = Wrapped(model).eval()

    enc = tokenizer(PROBES[0], return_tensors="pt", truncation=True, max_length=MAX_LEN)
    inputs = (enc["input_ids"], enc["attention_mask"], enc["token_type_ids"])
    print("exporting to ONNX ...")
    torch.onnx.export(
        wrapped,
        inputs,
        str(tmp_model),
        input_names=["input_ids", "attention_mask", "token_type_ids"],
        output_names=["last_hidden_state"],
        # The Kotlin side feeds one query at a time but of varying length, so
        # the sequence axis must stay dynamic or every query would have to be
        # padded to the length used at export.
        dynamic_axes={
            "input_ids": {0: "batch", 1: "seq"},
            "attention_mask": {0: "batch", 1: "seq"},
            "token_type_ids": {0: "batch", 1: "seq"},
            "last_hidden_state": {0: "batch", 1: "seq"},
        },
        opset_version=14,
        do_constant_folding=True,
        dynamo=False,
    )

    import onnx
    onnx.checker.check_model(onnx.load(str(tmp_model)))

    # --- verify against sentence-transformers -------------------------------
    import onnxruntime as ort

    sess = ort.InferenceSession(str(tmp_model), providers=["CPUExecutionProvider"])
    onnx_inputs = {i.name for i in sess.get_inputs()}
    print(f"graph inputs: {sorted(onnx_inputs)}")

    from sentence_transformers import SentenceTransformer
    st = SentenceTransformer(MODEL_ID)
    reference = st.encode(PROBES, normalize_embeddings=True)

    worst = 0.0
    for probe, ref in zip(PROBES, reference):
        e = tokenizer(probe, return_tensors="np", truncation=True, max_length=MAX_LEN)
        feed = {k: v.astype(np.int64) for k, v in e.items() if k in onnx_inputs}
        hidden = sess.run(["last_hidden_state"], feed)[0]
        got = l2_normalize(mean_pool(hidden, e["attention_mask"]))[0]
        cos = float(np.dot(got, ref))
        worst = max(worst, abs(1.0 - cos))
        print(f"  cos={cos:.6f}  {probe[:52]}")

    if worst > args.tolerance:
        tmp_model.unlink(missing_ok=True)
        print(f"\nREFUSING to write the model: worst |1-cos| = {worst:.2e} "
              f"exceeds {args.tolerance:g}.")
        print("The exported graph does not reproduce sentence-transformers, so "
              "every on-device ranking would be wrong in a way nothing else "
              "would catch.")
        return 1

    tmp_model.replace(final_model)

    # --- tokenizer assets ---------------------------------------------------
    # The Kotlin WordPiece implementation needs the vocabulary and the casing /
    # accent-stripping flags. Written next to the model so the two cannot drift.
    vocab_out = out_dir / "vocab.txt"
    saved = Path(tokenizer.save_vocabulary(str(out_dir))[0])
    if saved.resolve() != vocab_out.resolve():
        shutil.move(str(saved), str(vocab_out))

    cfg = tokenizer.init_kwargs
    (out_dir / "tokenizer_meta.txt").write_text(
        "\n".join([
            f"do_lower_case={bool(cfg.get('do_lower_case', True))}",
            f"strip_accents={cfg.get('strip_accents', None)}",
            f"unk_token={tokenizer.unk_token}",
            f"cls_token={tokenizer.cls_token}",
            f"sep_token={tokenizer.sep_token}",
            f"pad_token={tokenizer.pad_token}",
            f"max_len={MAX_LEN}",
            f"model_id={MODEL_ID}",
        ]) + "\n",
        encoding="utf-8",
    )

    size_mb = final_model.stat().st_size / (1024 * 1024)
    vocab_lines = sum(1 for _ in vocab_out.open(encoding="utf-8"))
    print(f"\nwrote {final_model}  ({size_mb:.1f} MB)")
    print(f"wrote {vocab_out}  ({vocab_lines} tokens)")
    print(f"worst |1-cos| vs sentence-transformers: {worst:.2e}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
