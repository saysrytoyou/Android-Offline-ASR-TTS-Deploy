# 🤖 Android 离线流式语音助手 (ASR & TTS)

这是一个基于 [Sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) 推理框架深度优化的 **Android 端侧完全离线语音组件**。本项目不仅实现了语音识别与合成功能，还针对“端侧算力瓶颈”和“模型幻觉”进行了商业级的底层架构重构，实现了**无缝连读、首字秒出、边说边识别**的极致体验。

![Platform](https://img.shields.io/badge/Platform-Android-green) ![Language](https://img.shields.io/badge/Language-Kotlin-blue) ![Engine](https://img.shields.io/badge/Engine-Sherpa--Onnx-orange) ![ASR](https://img.shields.io/badge/ASR-Paraformer-red) ![TTS](https://img.shields.io/badge/TTS-VITS(MeloTTS)-purple)

## ✨ 核心技术亮点

### 🎙️ 1. 高精度流式识别 (Paraformer ASR)
* **边说边出字**：采用非自回归的 Paraformer 流式双语架构，实现零延迟的实时语音转写。
* **免疫“复读机”幻觉**：彻底解决传统 Transducer 模型在环境底噪或静音时疯狂输出重复字的痛点。
* **动态端点检测 (VAD)**：精准判断用户说话停顿，自动断句并截断音频流。

### 🗣️ 2. 极致无缝播报 (Streaming TTS)
* **多核并行加速**：强行解锁 4 核 CPU 线程 (`numThreads = 4`) 进行底层张量计算，生成速度大幅提升。
* **双线程流水线 (Producer-Consumer)**：引入缓冲队列，后台拼命生成音频，前台按序无缝播放，彻底消除卡顿。
* **动态 VAD 静音切除**：针对模型自带的“首尾强制静音”，采用声学能量计算动态砍掉无声波形，实现如同真人呼吸般自然的连读节奏。
* **智能长句降级切分**：遇到超长复杂句自动在逗号处切分，确保低端机型上也能做到**首字极速响应**。

### ✨ 3. 智能排版流水线
* 内置 300MB 级别的 **CT-Transformer** 标点大模型。
* 用户说完松开按钮的瞬间，毫秒级为“裸文本”添加精准的逗号、句号、问号，并完美修复中英文排版。

---

## 📥 核心模型下载 (必读)

由于 GitHub 文件大小限制，本项目仓库**不包含**大型 `.onnx` 模型文件。请手动下载以下文件并严格按照目录结构放入 `app/src/main/assets/` 目录下。

| 组件 | 模型架构 | 下载链接 (国内直连) | 说明 |
| :--- | :--- | :--- | :--- |
| **ASR** | Paraformer 双语流式 | [encoder.onnx](https://hf-mirror.com/csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en/resolve/main/encoder.int8.onnx) ｜ [decoder.onnx](https://hf-mirror.com/csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en/resolve/main/decoder.int8.onnx) ｜ [tokens.txt](https://hf-mirror.com/csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en/resolve/main/tokens.txt) | **必下**：全部下载并放入 `asr` 文件夹中。 |
| **TTS** | MeloTTS (VITS) | [点击下载 TTS 资源压缩包](https://mirror.ghproxy.com/https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-melo-tts-zh_en.tar.bz2) | **极度重要**：解压后，请将 `dict` 文件夹内的所有字典文件**全部移出**，与模型平级存放！ |
| **Punct** | CT-Transformer | [点击下载 Punct 资源压缩包](https://mirror.ghproxy.com/https://github.com/k2-fsa/sherpa-onnx/releases/download/punctuation-models/sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12.tar.bz2) | 提供智能标点与大小写恢复功能。 |

---

## 📂 资源目录结构 (Assets)

配置完成后，您的 `assets` 目录必须与下方层级**严格一致**，否则会导致初始化崩溃或发音异常：

```text
app/src/main/assets/
├── asr/                      # 流式识别模型
│   ├── encoder.int8.onnx     
│   ├── decoder.int8.onnx
│   └── tokens.txt            
│
├── tts/                      # ⚠️ 此目录下所有文件必须直接平铺，不能有子文件夹
│   ├── vits-bilingual.onnx   # 注意：原包里的 model.onnx 需手动重命名为此文件
│   ├── tokens.txt
│   ├── lexicon.txt
│   ├── date.fst              # (原 dict 文件夹内文件)
│   ├── number.fst            # (原 dict 文件夹内文件，负责数字发音)
│   ├── phone.fst             # (原 dict 文件夹内文件)
│   ├── new_heteronym.fst     # (原 dict 文件夹内文件，负责多音字)
│   └── jieba.dict.utf8       # (原 dict 文件夹内文件)
│
└── punct/                    # 标点排版模型
    └── model.onnx
## 🛠️ 环境依赖

在您的 `app/build.gradle` 中引入 Sherpa-onnx 的 AAR 底层库：

```groovy
dependencies {
    // 引入本地 AAR 或通过 Maven 引入
    implementation files('libs/sherpa-onnx-1.12.23.aar') 
}

```

需在`AndroidManifest.xml` 中声明麦克风权限 `<uses-permission android:name="android.permission.RECORD_AUDIO" />`

---

## 📄 许可证

本项目基于 [Apache License 2.0](https://www.google.com/search?q=LICENSE) 开源。
底层推理引擎由伟大的 [Sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) 开源社区提供强力驱动。

