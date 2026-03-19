#!/usr/bin/env python3
"""
NLLB-200-distilled-600M → ONNX int8 변환 스크립트
=====================================================

HuggingFace의 facebook/nllb-200-distilled-600M 모델을 ONNX int8로 변환하여
Android에서 ONNX Runtime으로 직접 실행할 수 있는 형태로 패키징합니다.

요구사항:
    pip install optimum[exporters] onnxruntime onnxruntime-tools

사용법:
    python scripts/convert_nllb_onnx.py --output-dir /path/to/output

결과물 (--output-dir 안에 생성):
    encoder_model.onnx          NLLB 인코더 (int8 양자화)
    decoder_model_merged.onnx   NLLB 디코더 + KV-cache (int8 양자화)
    sentencepiece.bpe.model     SentencePiece 토크나이저
    tokenizer_config.json       언어 코드 매핑 등 설정
    nllb-600m.tar.bz2           위 파일들의 패키지 (앱에서 직접 다운로드)
"""

import argparse
import subprocess
import tarfile
import shutil
from pathlib import Path


MODEL_ID = "facebook/nllb-200-distilled-600M"

REQUIRED_FILES = [
    "encoder_model.onnx",
    "decoder_model_merged.onnx",
    "sentencepiece.bpe.model",
    "tokenizer_config.json",
]


def run(cmd: list[str], **kwargs):
    print(f"$ {' '.join(cmd)}")
    subprocess.run(cmd, check=True, **kwargs)


def export_onnx(output_dir: Path):
    """optimum-cli로 ONNX 내보내기."""
    onnx_dir = output_dir / "onnx_export"
    onnx_dir.mkdir(parents=True, exist_ok=True)

    run([
        "optimum-cli", "export", "onnx",
        "--model", MODEL_ID,
        "--task", "text2text-generation-with-past",
        "--opset", "14",
        str(onnx_dir),
    ])
    return onnx_dir


def quantize_int8(onnx_dir: Path, output_dir: Path):
    """ONNX 모델을 int8 동적 양자화."""
    from onnxruntime.quantization import quantize_dynamic, QuantType

    for src_name, dst_name in [
        ("encoder_model.onnx", "encoder_model.onnx"),
        ("decoder_model_merged.onnx", "decoder_model_merged.onnx"),
    ]:
        src = onnx_dir / src_name
        dst = output_dir / dst_name
        if not src.exists():
            raise FileNotFoundError(f"ONNX 내보내기 결과에 {src_name}이 없습니다. "
                                    "optimum 버전 및 --task 옵션을 확인하세요.")
        print(f"Quantizing {src_name} → {dst_name} (int8) …")
        quantize_dynamic(
            model_input=str(src),
            model_output=str(dst),
            weight_type=QuantType.QInt8,
            per_channel=False,
            reduce_range=False,
            optimize_model=True,
        )
        orig_mb  = src.stat().st_size / 1e6
        quant_mb = dst.stat().st_size / 1e6
        print(f"  {orig_mb:.0f} MB → {quant_mb:.0f} MB")


def copy_tokenizer(onnx_dir: Path, output_dir: Path):
    """토크나이저 파일 복사."""
    for name in ["sentencepiece.bpe.model", "tokenizer_config.json", "special_tokens_map.json"]:
        src = onnx_dir / name
        if src.exists():
            shutil.copy2(src, output_dir / name)
            print(f"Copied {name}")
        elif name not in ("special_tokens_map.json",):
            print(f"WARNING: {name} not found — tokenizer may be incomplete")


def package_tar_bz2(output_dir: Path):
    """결과물을 tar.bz2로 패키징."""
    archive_path = output_dir.parent / "nllb-600m.tar.bz2"
    with tarfile.open(archive_path, "w:bz2") as tar:
        for name in REQUIRED_FILES:
            f = output_dir / name
            if f.exists():
                tar.add(f, arcname=f"nllb-600m/{name}")
    size_mb = archive_path.stat().st_size / 1e6
    print(f"\n패키지 생성 완료: {archive_path}  ({size_mb:.0f} MB)")
    print("이 파일을 웹서버 또는 GitHub Releases에 업로드한 후")
    print("ModelRegistry.NLLB_600M.archiveUrl 을 업데이트하세요.")
    return archive_path


def main():
    parser = argparse.ArgumentParser(description="NLLB-200 → ONNX int8 변환")
    parser.add_argument("--output-dir", required=True, type=Path,
                        help="변환된 파일을 저장할 디렉터리")
    parser.add_argument("--skip-export", action="store_true",
                        help="이미 ONNX 내보내기가 완료된 경우 양자화 단계부터 시작")
    parser.add_argument("--skip-quantize", action="store_true",
                        help="양자화 없이 FP32 ONNX 그대로 패키징 (정확도 높지만 용량 큼)")
    parser.add_argument("--no-package", action="store_true",
                        help="tar.bz2 패키징 생략")
    args = parser.parse_args()

    output_dir: Path = args.output_dir
    output_dir.mkdir(parents=True, exist_ok=True)

    if not args.skip_export:
        print("=== 1/3  ONNX 내보내기 ===")
        onnx_dir = export_onnx(output_dir)
    else:
        onnx_dir = output_dir / "onnx_export"
        if not onnx_dir.exists():
            parser.error(f"--skip-export 지정 시 {onnx_dir} 가 존재해야 합니다")

    if not args.skip_quantize:
        print("\n=== 2/3  int8 양자화 ===")
        quantize_int8(onnx_dir, output_dir)
    else:
        print("\n=== 2/3  양자화 생략 — FP32 복사 ===")
        for name in ["encoder_model.onnx", "decoder_model_merged.onnx"]:
            shutil.copy2(onnx_dir / name, output_dir / name)

    print("\n=== 3/3  토크나이저 복사 ===")
    copy_tokenizer(onnx_dir, output_dir)

    # Verify
    missing = [n for n in REQUIRED_FILES if not (output_dir / n).exists()]
    if missing:
        print(f"\n경고: 다음 파일이 없습니다: {missing}")
        print("ModelRegistry.NLLB_600M.requiredFiles 와 비교해서 수동으로 확인하세요.")
    else:
        print("\n✓ 필수 파일 모두 존재")

    if not args.no_package:
        package_tar_bz2(output_dir)

    print("\n완료!")


if __name__ == "__main__":
    main()
