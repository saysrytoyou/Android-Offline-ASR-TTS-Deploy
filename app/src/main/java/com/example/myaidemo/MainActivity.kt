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
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var tvResult: TextView
    private lateinit var etInput: EditText
    private lateinit var btnTTS: Button
    private lateinit var btnASR: Button

    // --- AI 引擎实例定义 ---
    private var ttsEngine: OfflineTts? = null
    private var asrRecognizer: OnlineRecognizer? = null
    private var asrStream: OnlineStream? = null
    private var punctEngine: OfflinePunctuation? = null

    @Volatile
    private var isRecording = false
    private var audioRecord: AudioRecord? = null
    private var accumulatedAsrText = ""

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvResult = findViewById(R.id.tvResult)
        etInput = findViewById(R.id.etInput)
        btnTTS = findViewById(R.id.btnTTS)
        btnASR = findViewById(R.id.btnASR)

        checkPermission()

        // 后台异步加载所有 AI 模型，避免阻塞主线程
        thread {
            initTTS()
            initStreamingASR()
            initPunctuation()
            runOnUiThread {
                Toast.makeText(this, "AI 引擎初始化完毕", Toast.LENGTH_SHORT).show()
            }
        }

        btnTTS.setOnClickListener {
            val text = etInput.text.toString().ifEmpty {
                "你好，欢迎体验端侧大模型。这是一段用来测试极限连贯度的长难句文本，请仔细听一下在多核加速和端点静音切除的加持下，它是不是像真人说话一样丝滑自然呢？测试结束。"
            }
            tvResult.text = "正在流式合成与播放..."
            startStreamingTTS(text)
        }

        btnASR.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    btnASR.text = "正在识别..."
                    startRecording()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    btnASR.text = "正在排版..."
                    stopRecording()
                    true
                }
                else -> false
            }
        }
    }

    // --- 1. 初始化 TTS引擎 (开启多核并行加速) ---
    private fun initTTS() {
        try {
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = "tts/vits-bilingual.onnx",
                        tokens = "tts/tokens.txt",
                        lexicon = "tts/lexicon.txt",
                        dictDir = "tts"
                    ),
                    // 分配 4 个 CPU 线程进行推理，极大地提升生成速度
                    numThreads = 4,
                    debug = false
                )
            )
            ttsEngine = OfflineTts(assets, config)
        } catch (e: Exception) {
            Log.e("MyAiDemo", "TTS Init Error: ${e.message}")
        }
    }

    // --- 2. 初始化标点模型 (用于恢复标点与大小写) ---
    private fun initPunctuation() {
        try {
            val config = OfflinePunctuationConfig(
                model = OfflinePunctuationModelConfig(ctTransformer = "punct/model.onnx")
            )
            punctEngine = OfflinePunctuation(assets, config)
        } catch (e: Exception) {}
    }

    // --- 3. 初始化流式 ASR (高精度 Paraformer 架构) ---
    private fun initStreamingASR() {
        try {
            val config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    paraformer = OnlineParaformerModelConfig(
                        encoder = "asr/encoder.int8.onnx",
                        decoder = "asr/decoder.int8.onnx"
                    ),
                    tokens = "asr/tokens.txt",
                    modelType = "paraformer"
                ),
                enableEndpoint = true, // 开启端点静音检测
                endpointConfig = EndpointConfig(
                    rule1 = EndpointRule(false, 2.4f, 0.0f),
                    rule2 = EndpointRule(true, 1.2f, 0.0f),
                    rule3 = EndpointRule(false, 0.0f, 20.0f)
                )
            )
            asrRecognizer = OnlineRecognizer(assets, config)
        } catch (e: Exception) {
            Log.e("MyAiDemo", "ASR Init Error: ${e.message}")
        }
    }

    // --- 4. 实时流式录音识别 (边说边出字) ---
    private fun startRecording() {
        if (isRecording) return
        isRecording = true
        accumulatedAsrText = ""

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

                    val stream = asrStream ?: continue
                    val recognizer = asrRecognizer ?: continue

                    // 持续将音频特征喂给模型
                    stream.acceptWaveform(floatBuffer, sampleRate = 16000)

                    while (recognizer.isReady(stream)) {
                        recognizer.decode(stream)
                    }

                    val currentSegment = recognizer.getResult(stream).text
                    val isEndpoint = recognizer.isEndpoint(stream)

                    // 实时更新 UI 上屏
                    val displayText = accumulatedAsrText + currentSegment
                    if (displayText.isNotEmpty()) {
                        runOnUiThread { tvResult.text = displayText }
                    }

                    // 检测到断句时，固化当前段落
                    if (isEndpoint) {
                        accumulatedAsrText += currentSegment
                        recognizer.reset(stream)
                    }
                }
            }
        }
    }

    // --- 5. 录音结束 (解码尾音与调用排版) ---
    private fun stopRecording() {
        isRecording = false
        try { audioRecord?.stop(); audioRecord?.release() } catch (e: Exception) {}
        audioRecord = null

        thread {
            val stream = asrStream ?: return@thread
            val recognizer = asrRecognizer ?: return@thread

            // 压入空音频告知识别流结束
            stream.acceptWaveform(FloatArray(0), 16000)
            while (recognizer.isReady(stream)) {
                recognizer.decode(stream)
            }

            val finalSegment = recognizer.getResult(stream).text
            val fullRawText = accumulatedAsrText + finalSegment

            if (fullRawText.isNotEmpty()) {
                val finalPrettyText = smartProcessText(fullRawText)

                runOnUiThread {
                    tvResult.text = finalPrettyText
                    etInput.setText(finalPrettyText)
                    btnASR.text = "ASR: 按住说话"
                }
            } else {
                runOnUiThread { btnASR.text = "ASR: 按住说话" }
            }
            stream.release()
        }
    }

    // --- 6. 智能文本排版与清理 (标点去重与修复) ---
    private fun smartProcessText(rawText: String): String {
        if (rawText.isBlank()) return ""
        var cleanText = rawText.replace(Regex("[，。？！,.?!:;]"), " ").trim()
        cleanText = cleanText.lowercase()
        var processed = punctEngine?.addPunctuation(cleanText) ?: cleanText

        processed = processed
            .replace(Regex("([，。？！,.?!])\\1+"), "$1")
            .replace("。？", "？").replace("？。", "？").replace("！。", "！").replace("，。", "。")
            .trimStart { it in "，。？！,.?!" }

        return processed
    }

    // --- 7. 终极性能版：流式无缝 TTS (双线程 + VAD裁剪 + 首句秒出) ---
    private fun startStreamingTTS(text: String) {
        thread {
            ttsEngine?.let { tts ->
                var cleanText = text.replace(Regex("<.*?>"), "").uppercase()

                if (!cleanText.matches(Regex(".*[。？！.?!].*"))) {
                    cleanText += "。"
                }

                // 动态分句策略：优先在句尾切分；若长难句(>25字)则按逗号切分，防内存阻塞
                val majorChunks = cleanText.split(Regex("(?<=[。？！.?!])")).filter { it.isNotBlank() }
                val finalChunks = mutableListOf<String>()
                for (chunk in majorChunks) {
                    if (chunk.length > 25) {
                        finalChunks.addAll(chunk.split(Regex("(?<=[，、,])")).filter { it.isNotBlank() })
                    } else {
                        finalChunks.add(chunk)
                    }
                }

                // 线程安全的音频缓冲队列
                val audioQueue = LinkedBlockingQueue<FloatArray>()
                val poisonPill = FloatArray(0)
                var currentSampleRate = 22050

                // 👉 线程 1 (生产者)：生成音频并动态切除端点静音 (VAD Trimming)
                thread {
                    for (chunk in finalChunks) {
                        if (chunk.trim { it in "，。？！,.?!、 " }.isEmpty()) continue

                        val audio = tts.generate(chunk, sid = 0, speed = 0.85f)
                        if (audio.samples.isNotEmpty()) {
                            currentSampleRate = audio.sampleRate
                            val samples = audio.samples

                            // 动态抹除模型默认生成的拼接静音 (阈值 0.005f)
                            var startIdx = 0
                            var endIdx = samples.size - 1
                            val threshold = 0.005f

                            while (startIdx < samples.size && Math.abs(samples[startIdx]) < threshold) startIdx++
                            while (endIdx > startIdx && Math.abs(samples[endIdx]) < threshold) endIdx--

                            if (startIdx <= endIdx) {
                                audioQueue.put(samples.copyOfRange(startIdx, endIdx + 1))
                            }
                        }
                    }
                    audioQueue.put(poisonPill)
                }

                var track: AudioTrack? = null

                try {
                    // 👉 线程 2 (消费者)：拿到第一句立刻开播，后续无缝追赶
                    val firstAudio = audioQueue.take()
                    if (firstAudio.isNotEmpty()) {
                        val minBufferSize = AudioTrack.getMinBufferSize(
                            currentSampleRate,
                            AudioFormat.CHANNEL_OUT_MONO,
                            AudioFormat.ENCODING_PCM_FLOAT
                        )
                        // 将缓冲区放大 4 倍，构建抗算力波动的蓄水池
                        track = AudioTrack(
                            android.media.AudioManager.STREAM_MUSIC,
                            currentSampleRate,
                            AudioFormat.CHANNEL_OUT_MONO,
                            AudioFormat.ENCODING_PCM_FLOAT,
                            minBufferSize * 4,
                            AudioTrack.MODE_STREAM
                        )
                        track.play()

                        track.write(firstAudio, 0, firstAudio.size, AudioTrack.WRITE_BLOCKING)

                        // 循环接管队列
                        while (true) {
                            val samples = audioQueue.take()
                            if (samples.isEmpty()) break
                            track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                        }
                        Thread.sleep(800)
                    }
                } catch (e: Exception) {
                    Log.e("MyAiDemo", "TTS Playback Error: ${e.message}")
                } finally {
                    track?.stop()
                    track?.release()
                }

                runOnUiThread {
                    tvResult.text = "播放完毕"
                }
            }
        }
    }

    // --- 8. 麦克风动态权限检查 ---
    private fun checkPermission() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
    }
}