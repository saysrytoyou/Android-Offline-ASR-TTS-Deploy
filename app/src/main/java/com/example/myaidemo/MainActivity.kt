package com.example.myaidemo

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.k2fsa.sherpa.onnx.*
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var tvResult: TextView
    private lateinit var etInput: EditText
    private lateinit var btnTTS: Button
    private lateinit var btnASR: Button

    // --- AI 引擎定义 ---
    private var ttsEngine: OfflineTts? = null
    // SenseVoice 使用离线识别器 (非流式)
    private var asrRecognizer: OfflineRecognizer? = null
    private var asrStream: OfflineStream? = null
    private var punctEngine: OfflinePunctuation? = null

    @Volatile
    private var isRecording = false
    private var audioRecord: AudioRecord? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 绑定 UI 控件
        tvResult = findViewById(R.id.tvResult)
        etInput = findViewById(R.id.etInput)
        btnTTS = findViewById(R.id.btnTTS)
        btnASR = findViewById(R.id.btnASR)

        // 检查录音权限
        checkPermission()

        // 后台初始化所有 AI 模型
        thread {
            initTTS()
            initHighAccuracyASR() // 初始化 SenseVoice
            initPunctuation()     // 初始化标点模型
            runOnUiThread {
                Toast.makeText(this, "AI 引擎全部加载完毕", Toast.LENGTH_SHORT).show()
            }
        }

        // TTS 按钮点击事件
        btnTTS.setOnClickListener {
            val text = etInput.text.toString().ifEmpty { "123456" }
            tvResult.text = "正在播放: $text"
            startTTS(text)
        }

        // ASR 按钮触摸事件 (按住说话，松开识别)
        btnASR.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    btnASR.text = "正在听... (说完请松手)"
                    startRecording()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    btnASR.text = "正在识别..."
                    stopRecording()
                    true
                }
                else -> false
            }
        }
    }

    // --- 1. 初始化 TTS (MeloTTS) ---
    private fun initTTS() {
        try {
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = "tts/vits-bilingual.onnx",
                        tokens = "tts/tokens.txt",
                        lexicon = "tts/lexicon.txt",
                        dictDir = "tts" // 关键：确保 date.fst 等文件都在 assets/tts 下
                    )
                )
            )
            ttsEngine = OfflineTts(assets, config)
        } catch (e: Exception) {
            Log.e("MyAiDemo", "TTS Init Error: ${e.message}")
        }
    }

    // --- 2. 初始化标点模型 (CT-Transformer) ---
    private fun initPunctuation() {
        try {
            val config = OfflinePunctuationConfig(
                model = OfflinePunctuationModelConfig(
                    ctTransformer = "punct/model.onnx"
                )
            )
            punctEngine = OfflinePunctuation(assets, config)
        } catch (e: Exception) {
            Log.e("MyAiDemo", "Punct Init Error: ${e.message}")
        }
    }

    // --- 3. 初始化高精度 ASR (SenseVoice) ---
    private fun initHighAccuracyASR() {
        try {
            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    // SenseVoice 专属配置
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = "asr/model.int8.onnx",
                        language = "auto" // 自动识别中英
                    ),
                    // 通用配置 (注意 tokens 在这里)
                    tokens = "asr/tokens.txt",
                    modelType = "sense_voice",
                    debug = true
                )
            )
            asrRecognizer = OfflineRecognizer(assets, config)
            Log.d("MyAiDemo", "SenseVoice 初始化成功")
        } catch (e: Exception) {
            Log.e("MyAiDemo", "ASR Init Error: ${e.message}")
            e.printStackTrace()
        }
    }

    // --- 4. 开始录音 (喂数据给 SenseVoice) ---
    private fun startRecording() {
        if (isRecording) return
        isRecording = true

        // 创建一个新的识别流
        asrStream = asrRecognizer?.createStream()

        thread {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return@thread

            val bufferSize = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
            audioRecord?.startRecording()

            val buffer = ShortArray(bufferSize)
            val floatBuffer = FloatArray(bufferSize)

            while (isRecording) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    for (i in 0 until read) { floatBuffer[i] = buffer[i] / 32768.0f }
                    // 持续将音频数据喂给流
                    asrStream?.acceptWaveform(floatBuffer, sampleRate = 16000)
                }
            }
        }
    }

    // --- 5. 停止录音并获取结果 (包含智能清洗逻辑) ---
    private fun stopRecording() {
        isRecording = false
        try { audioRecord?.stop(); audioRecord?.release() } catch (e: Exception) {}
        audioRecord = null

        thread {
            val stream = asrStream ?: return@thread
            val recognizer = asrRecognizer ?: return@thread

            // 告诉流输入结束
            stream.acceptWaveform(FloatArray(0), 16000)

            // 开始解码 (SenseVoice 此时才真正开始工作)
            try {
                recognizer.decode(stream)
                val result = recognizer.getResult(stream)
                val rawText = result.text

                if (rawText.isNotEmpty()) {
                    // ★★★ 调用智能清洗函数：解决大小写、标点重复、逻辑错误 ★★★
                    val finalPrettyText = smartProcessText(rawText)

                    runOnUiThread {
                        tvResult.text = finalPrettyText
                        etInput.setText(finalPrettyText) // 自动填入输入框，方便测试 TTS
                        btnASR.text = "ASR: 按住说话"
                    }
                } else {
                    runOnUiThread {
                        tvResult.text = "未检测到有效语音"
                        btnASR.text = "ASR: 按住说话"
                    }
                }
            } catch (e: Exception) {
                Log.e("MyAiDemo", "识别出错: ${e.message}")
            } finally {
                stream.release()
            }
        }
    }

    // --- 6. 智能文本清洗函数 (核心优化) ---
    private fun smartProcessText(rawText: String): String {
        if (rawText.isBlank()) return ""

        // 1. 【前处理】暴力清洗：去掉原文本中可能自带的标点符号
        var cleanText = rawText.replace(Regex("[，。？！,.?!:;]"), " ").trim()

        // 2. 【转小写】：配合标点模型处理英文大小写 (HELLO -> hello)
        cleanText = cleanText.lowercase()

        // 3. 【加标点】：调用模型生成标点
        var processed = punctEngine?.addPunctuation(cleanText) ?: cleanText

        // 4. 【后处理】去重与纠错：修复模型“幻觉”导致的怪异符号
        processed = processed
            .replace(Regex("([，。？！,.?!])\\1+"), "$1") // 去重
            .replace("。？", "？") // 修复奇怪组合
            .replace("？。", "？")
            .replace("！。", "！")
            .replace("，。", "。")
            .trimStart { it in "，。？！,.?!" } // 去掉句首标点

        return processed
    }

    // --- 7. 执行 TTS [修复英文不读的问题] ---
    private fun startTTS(text: String) {
        thread {
            ttsEngine?.let { tts ->
                // 1. 过滤掉 SenseVoice 可能产生的特殊标签
                var cleanText = text.replace(Regex("<.*?>"), "")

                // 2. ★★★ 核心修复：强制转大写 ★★★
                // 离线字典通常只认识 "HELLO" 不认识 "hello"
                // 加上这一行，"ok" 变成 "OK"，"good morning" 变成 "GOOD MORNING"，就能读出来了！
                cleanText = cleanText.uppercase()

                // 3. (可选) 处理中文句号紧跟英文的情况，加个空格防止连读太快
                cleanText = cleanText.replace("。", "。 ")

                val audio = tts.generate(cleanText, sid = 0, speed = 1.0f)
                if (audio.samples.isNotEmpty()) {
                    val track = AudioTrack(
                        android.media.AudioManager.STREAM_MUSIC,
                        audio.sampleRate,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_FLOAT,
                        audio.samples.size * 4,
                        AudioTrack.MODE_STATIC
                    )
                    track.write(audio.samples, 0, audio.samples.size, AudioTrack.WRITE_BLOCKING)
                    track.play()
                } else {
                    Log.e("MyAiDemo", "TTS 生成音频为空 (可能是文本包含无法发音的字符)")
                }
            }
        }
    }

    // --- 8. 权限检查 ---
    private fun checkPermission() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
    }
}