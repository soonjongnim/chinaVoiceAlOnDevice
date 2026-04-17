# ChinaVoiceAIOnDevice

오프라인 상태에서 작동하는 **중국어 음성 인식(STT) 및 한국어-중국어 양방향 번역** 안드로이드 애플리케이션입니다.

## 🚀 주요 기능

1.  **오프라인 중국어 음성 인식 (STT)**
    *   `Sherpa-Onnx` 엔진과 `Zipformer` 모델을 사용하여 기기 내에서 실시간으로 중국어 음성을 인식합니다.
2.  **오프라인 한국어-중국어 번역**
    *   Meta의 `NLLB-200` 모델을 ONNX Runtime Mobile에서 구동하여 인터넷 연결 없이 한국어와 중국어를 양방향 번역합니다.
3.  **한글 독음 자동 표시**
    *   번역된 중국어 한자 옆에 한국어 발음(한글)을 자동으로 병기하여 읽기 편리하게 도와줍니다.

## 🛠 기술 스택

*   **언어**: Kotlin, C++ (JNI)
*   **엔진**: [Sherpa-Onnx](https://github.com/k2-fsa/sherpa-onnx) (C-API), [ONNX Runtime Mobile](https://onnxruntime.ai/)
*   **모델**:
    *   STT: Zipformer (Chinese)
    *   Translation: NLLB-200 (Distilled 600M, int8 quantized)

## 📦 필수 파일 준비 (Prerequisites)

이 저장소에는 대용량 모델 파일(`*.onnx`)이 포함되어 있지 않습니다. 앱을 정상적으로 실행하려면 다음 파일들을 준비해야 합니다.

### 1. STT 모델 파일 배치
`app/src/main/assets/sherpa-onnx-zipformer-multi-zh-hans/` 폴더에 다음 파일들을 수동으로 추가해야 합니다:
*   `encoder-epoch-20-avg-1.onnx`
*   `decoder-epoch-20-avg-1.onnx`
*   `joiner-epoch-20-avg-1.onnx`
*   `tokens.txt`

### 2. 번역 모델 자동 다운로드
번역 모델(`NLLB-200`)은 앱 최초 실행 시 [Hugging Face](https://huggingface.co/soon9086/onnx_nllb200)에서 자동으로 다운로드됩니다. (약 4개의 파일, 총 수백 MB)

## 📖 사용 방법

1.  **음성 인식 및 번역**
    *   하단의 **"STT 음성 인식 및 번역 시작"** 버튼을 누릅니다.
    *   기본 내장된 테스트용 웨이브 파일(`0.wav`)을 인식하거나 마이크 권한 승인 후 음성을 인식합니다.
    *   인식된 중국어가 텍스트로 표시되고, 번역 엔진이 작동하여 결과가 출력됩니다.
2.  **직접 입력 번역**
    *   상단 입력창에 문장을 입력하고, 상황에 맞는 번역 버튼(`텍스트를 중국어로 번역` 또는 `중국어를 한국어로 번역`)을 누르면 오프라인 번역이 수행됩니다. 번역 결과 텍스트를 길게 누르면 클립보드에 복사할 수 있습니다.

## ⚠️ 주의 사항

*   대용량 모델을 로딩하므로 기기의 RAM 용량(최소 4GB 이상 권장)에 따라 초기 로딩 시간이 소요될 수 있습니다.
*   `64-bit (arm64-v8a)` 기기를 권장합니다.

---
**개발자**: [soonjongnim](https://github.com/soonjongnim)
