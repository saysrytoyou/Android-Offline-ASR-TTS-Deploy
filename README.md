# 🤖 Android-Offline-ASR-TTS-Deploy

A complete, **offline** Android project demonstrating how to deploy bilingual (Chinese/English) Automatic Speech Recognition (ASR) and Text-to-Speech (TTS) models using [Sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx).

> **Note**: This project runs entirely on-device without internet access.

## 🚀 Key Features

* **Offline Speech Recognition (ASR)**: Real-time streaming recognition using the `Zipformer` bilingual model.
* **Offline Text-to-Speech (TTS)**: High-quality speech synthesis using `MeloTTS` (VITS).
* **Bilingual Support**: Supports mixed Mandarin Chinese and English.
* **Smart Post-Processing**:
* Auto-punctuation (adds commas, periods, question marks).
* Auto-capitalization for English sentences.
* Number/Date normalization (reads "123" as "one hundred...").



## 📂 Project Structure (Assets)

To make the app work, you **must** manually download the models and place them in the `app/src/main/assets/` directory. The structure must be **flat** for the TTS folder (do not use subfolders like `dict`).

```text
app/src/main/assets/
├── asr/                                # ASR Model (Zipformer Bilingual)
│   ├── encoder-epoch-99-avg-1.int8.onnx
│   ├── decoder-epoch-99-avg-1.int8.onnx
│   ├── joiner-epoch-99-avg-1.int8.onnx
│   └── tokens.txt
│
├── tts/                                # TTS Model (MeloTTS) - FLATTENED!
│   ├── vits-bilingual.onnx             # Renamed from model.onnx
│   ├── tokens.txt                      # From MeloTTS package
│   ├── lexicon.txt
│   ├── date.fst                        # ⚠️ Must be in assets/tts/ root
│   ├── number.fst
│   ├── phone.fst
│   ├── new_heteronym.fst
│   └── jieba.dict.utf8                 # Moved out from 'dict' folder
│
└── punct/                              # Punctuation Model
    └── model.onnx                      # CT-Transformer

```

## 🛠️ Setup & Installation

### 1. Requirements

* Android Studio (Ladybug or newer recommended).
* Physical Android Device (Tablets/Phones) with ARM architecture.
* **Permissions**: Microphone access.

### 2. Download Models

You need to download the following pre-trained models:

1. **ASR (Bilingual)**: `sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20`
* [Download Link (HuggingFace)](https://www.google.com/search?q=https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/tree/main)


2. **TTS (MeloTTS Zh_En)**: `vits-melo-tts-zh_en`
* [Download Link (GitHub Release)](https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-melo-tts-zh_en.tar.bz2)
* *Important*: Extract and rename `model.onnx` to `vits-bilingual.onnx`. Move all `.fst` files from `dict/` to `assets/tts/`.


3. **Punctuation**: `sherpa-onnx-punct-ct-transformer-zh-en-vocab272727`
* [Download Link (GitHub Release)](https://www.google.com/search?q=https://github.com/k2-fsa/sherpa-onnx/releases/download/punctuation-models/sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12.tar.bz2)



### 3. Dependencies (AAR)

Since the library is not fully available on Maven Central for all versions, we use a local `.aar` file.

1. Download `sherpa-onnx-1.12.23.aar` (or latest).
2. Place it in `app/libs/`.
3. In `app/build.gradle`:
```groovy
dependencies {
    implementation files('libs/sherpa-onnx-1.12.23.aar')
}

```



## 📱 How to Use

1. **Launch the App**: Accept the microphone permission prompt.
2. **ASR (Speech to Text)**:
* Press and **hold** the "ASR" button.
* Speak (e.g., "Hello world", "你好世界").
* Release the button. The text will appear with correct punctuation and capitalization (e.g., "Hello world.").


3. **TTS (Text to Speech)**:
* Type text in the input box (e.g., "Welcome to 2026").
* Tap the "TTS" button to hear it spoken.



## ⚠️ Troubleshooting FAQ

* **Q: The app crashes immediately on startup.**
* A: Check if your `assets` folder structure matches the diagram above exactly. Check filenames in `MainActivity.kt`.


* **Q: It won't read numbers (e.g., "123").**
* A: You likely kept `number.fst` inside a `dict` folder. Move it to `assets/tts/` directly and ensure `dictDir = "tts"` is set in the code. Reinstall the app to clear the cache.


* **Q: English text is all uppercase (HELLO WORLD).**
* A: Ensure the `punct/model.onnx` is loaded correctly. The code uses the punctuation model to fix capitalization.



## 📄 License

Apache 2.0

---

**Credits**: Based on the amazing work by [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx).
