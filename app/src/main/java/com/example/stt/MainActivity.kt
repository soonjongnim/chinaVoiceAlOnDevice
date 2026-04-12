package com.example.stt

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private external fun initModel(modelDir: String)
    private external fun recognize(audioData: FloatArray): String

    private lateinit var resultTextView: TextView
    private var translator: TranslationEngine? = null
    private var isSttInitialized = false

    // TTS (Text-to-Speech) 관련
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var lastTranslatedText: String? = null  // 괄호(한글 발음) 제거 전 원문 중국어

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        translator = TranslationEngine()

        // TTS 초기화 (중국어)
        tts = TextToSpeech(this) { status ->
            Log.d("TTS", "TTS onInit callback, status=$status")
            if (status == TextToSpeech.SUCCESS) {
                // 사용 가능한 언어 로그 출력 (디버깅용)
                val availableLocales = tts?.availableLanguages
                Log.d("TTS", "사용 가능한 TTS 언어 수: ${availableLocales?.size ?: 0}")
                availableLocales?.forEach { loc ->
                    Log.d("TTS", "  - ${loc.displayName} (${loc.language}_${loc.country})")
                }

                // 여러 중국어 Locale을 순서대로 시도
                val chineseLocales = listOf(
                    Locale.SIMPLIFIED_CHINESE,       // zh_CN
                    Locale.CHINESE,                  // zh
                    Locale("zh", "CN"),
                    Locale("cmn", "CN"),              // 일부 TTS 엔진에서 사용
                )
                
                var success = false
                for (locale in chineseLocales) {
                    val result = tts?.setLanguage(locale)
                    Log.d("TTS", "Locale ${locale} 시도 → result=$result")
                    if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                        isTtsReady = true
                        tts?.setSpeechRate(0.9f)  // 약간 느리게 설정 (학습용)
                        Log.d("TTS", "✅ 중국어 TTS 초기화 성공 (Locale: $locale)")
                        success = true
                        break
                    }
                }
                
                if (!success) {
                    Log.e("TTS", "❌ 중국어 TTS 데이터를 찾을 수 없습니다. 설치를 시도합니다.")
                    runOnUiThread {
                        Toast.makeText(this, "중국어 음성 데이터를 설치합니다.\n설치 후 앱을 다시 실행하세요.", Toast.LENGTH_LONG).show()
                        // TTS 데이터 설치 화면 열기
                        try {
                            val installIntent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
                            startActivity(installIntent)
                        } catch (e: Exception) {
                            Log.e("TTS", "TTS 설치 화면 열기 실패", e)
                            Toast.makeText(this, "설정 > 접근성 > TTS에서\n중국어 음성 데이터를 설치하세요.", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } else {
                Log.e("TTS", "❌ TTS 엔진 초기화 자체 실패: status=$status")
                runOnUiThread {
                    Toast.makeText(this, "TTS 엔진 초기화 실패.\n설정 > TTS 엔진을 확인하세요.", Toast.LENGTH_LONG).show()
                }
            }
        }

        // 동적 UI 생성
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 64, 32, 32)
        }
        
        resultTextView = TextView(this).apply {
            text = "버튼을 눌러 음성 인식을 테스트하거나 텍스트를 직접 입력하세요."
            textSize = 18f
            setPadding(0, 0, 0, 32)
        }
        
        val inputEditText = android.widget.EditText(this).apply {
            hint = "한국어 번역할 텍스트를 입력하세요"
            setPadding(0, 0, 0, 32)
        }
        
        val translateTextButton = Button(this).apply {
            text = "텍스트를 중국어로 번역"
        }

        // 🔊 음성 재생 버튼
        val speakButton = Button(this).apply {
            text = "🔊 번역 음성 재생"
            isEnabled = false
        }

        speakButton.setOnClickListener {
            speakChinese(lastTranslatedText)
        }
        
        translateTextButton.setOnClickListener {
            val textToTranslate = inputEditText.text.toString()
            if (textToTranslate.isBlank()) return@setOnClickListener

            resultTextView.text = "입력값: $textToTranslate\n\n번역 중..."
            translateTextButton.isEnabled = false
            speakButton.isEnabled = false

            Thread {
                try {
                    // 사용자 지정 분리 모델 (Kotlin 엔진) 초기화
                    val nllbModelDir = java.io.File(cacheDir, "onnx_nllb200")
                    if (!translator!!.initTranslation(nllbModelDir.absolutePath)) {
                        runOnUiThread {
                            resultTextView.text = "❌ 번역 초기화 실패. 먼저 STT 버튼을 눌러주세요."
                            translateTextButton.isEnabled = true
                        }
                        return@Thread
                    }

                    // Kotlin 기반 번역 호출
                    val translatedText = translator!!.translate(textToTranslate)

                    // 번역 결과에서 중국어 원문만 추출 (괄호 안 한글 발음 제거)
                    lastTranslatedText = extractChineseText(translatedText)
                    
                    runOnUiThread {
                        resultTextView.text = "입력값: $textToTranslate\n\n번역값(중국어): $translatedText"
                        translateTextButton.isEnabled = true
                        speakButton.isEnabled = lastTranslatedText != null
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        resultTextView.text = "❌ 번역 실패: ${e.message}"
                        translateTextButton.isEnabled = true
                    }
                }
            }.start()
        }
        
        val testButton = Button(this).apply {
            text = "STT 음성 인식 및 번역 시작"
        }
        
        testButton.setOnClickListener { view ->
            val button = view as Button
            resultTextView.text = "모델 설정 및 다운로드 확인 중..."
            button.isEnabled = false // Disable it to prevent multiple clicks!
            translateTextButton.isEnabled = false
            
            Thread {
                try {
                    Log.d("STT_App", "에셋 복사 및 다운로드 확인 시작...")
                    copyAssetsToCache()
                    
                    val nllbModelDir = java.io.File(cacheDir, "onnx_nllb200")
                    if (!downloadModels(nllbModelDir)) {
                        Log.e("STT_App", "다운로드 실패")
                        runOnUiThread { 
                            button.isEnabled = true 
                            translateTextButton.isEnabled = true
                        }
                        return@Thread
                    }

                    // STT 엔진 C++ 초기화 (중복 방지)
                    if (!isSttInitialized) {
                        Log.d("STT_App", "STT 네이티브 엔진 초기화 시작...")
                        runOnUiThread { resultTextView.text = "STT 엔진 로딩 중..." }
                        initModel(cacheDir.absolutePath)
                        isSttInitialized = true
                        Log.d("STT_App", "STT 엔진 초기화 성공")
                    } else {
                        Log.d("STT_App", "STT 네이티브 엔진이 이미 로드되어 동작 중입니다.")
                    }

                    // 번역 엔진 Kotlin 초기화
                    Log.d("STT_App", "ONNX Kotlin 번역 엔진 초기화 시작...")
                    translator!!.initTranslation(nllbModelDir.absolutePath)
                    
                    val sttResult = testWithWavFile("sherpa-onnx-zipformer-multi-zh-hans/0.wav")
                    Log.d("STT_App", "STT 결과: $sttResult")
                    
                    runOnUiThread {
                        resultTextView.text = "STT 완료: $sttResult\n\n중국어 번역 중..."
                    }
                    
                    // Kotlin 번역 호출
                    val translatedText = translator!!.translate(sttResult)
                    Log.d("STT_App", "번역 완료: $translatedText")

                    // 번역 결과에서 중국어 원문만 추출
                    lastTranslatedText = extractChineseText(translatedText)
                    
                    runOnUiThread {
                        resultTextView.text = "STT (입력값): $sttResult\n\n번역값(중국어) 결과: $translatedText"
                        button.isEnabled = true
                        translateTextButton.isEnabled = true
                        speakButton.isEnabled = lastTranslatedText != null
                    }
                    
                } catch (e: OutOfMemoryError) {
                    Log.e("STT_App", "OOM 발생!", e)
                    runOnUiThread {
                        resultTextView.text = "❌ 메모리 부족: 엔진 로딩이 불가능합니다.\n다른 앱을 종료하고 시도해 보세요."
                        button.isEnabled = true
                        translateTextButton.isEnabled = true
                    }
                } catch (e: Exception) {
                    Log.e("STT_App", "예외 발생", e)
                    runOnUiThread {
                        resultTextView.text = "❌ 실행 실패: ${e.message}"
                        button.isEnabled = true
                        translateTextButton.isEnabled = true
                    }
                }
            }.start()
        }
        
        layout.addView(inputEditText)
        layout.addView(translateTextButton)
        layout.addView(resultTextView)
        layout.addView(speakButton)
        layout.addView(testButton)
        setContentView(layout)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tts?.stop()
        tts?.shutdown()
        translator?.close()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // 권한 결과 처리가 필요하면 이곳에 작성 (현재는 버튼을 누를 때 초기화하므로 비워둬도 무방)
    }

    private fun copyAssetsToCache() {
        val sttDirName = "sherpa-onnx-zipformer-multi-zh-hans"
        val sttFiles = arrayOf(
            "encoder-epoch-20-avg-1.onnx",
            "decoder-epoch-20-avg-1.onnx",
            "joiner-epoch-20-avg-1.onnx",
            "tokens.txt"
        )
        for (filename in sttFiles) {
            val outFile = java.io.File(cacheDir, filename)
            if (!outFile.exists()) {
                assets.open("$sttDirName/$filename").use { input ->
                    java.io.FileOutputStream(outFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
        // 번역 모델(NLLB)은 용량이 너무 커서 APK에 포함하지 않고, 사용자가 직접 
        // 번역 모델(NLLB)은 허깅페이스에서 다운로드합니다.
    }

    private fun downloadModels(nllbDir: java.io.File): Boolean {
        if (!nllbDir.exists()) nllbDir.mkdirs()
        
        // 다운로드할 파일 리스트와 URL 맵핑 (사용자 지정 분리 모델)
        val baseUrl = "https://huggingface.co/soon9086/onnx_nllb200/resolve/main/"
        val fileMap = mapOf(
            "tokenizer.json" to baseUrl + "tokenizer.json?download=true",
            "encoder_model_int8.onnx" to baseUrl + "encoder_model_int8.onnx?download=true",
            "decoder_model_int8.onnx" to baseUrl + "decoder_model_int8.onnx?download=true",
            "sentencepiece.bpe.model" to baseUrl + "sentencepiece.bpe.model?download=true"
        )
        
        for ((fileName, url) in fileMap) {
            val file = java.io.File(nllbDir, fileName)
            
            try {
                runOnUiThread {
                    resultTextView.text = "필수 데이터 다운로드 시작: $fileName\n(총 4개 파일 중 하나...)"
                }
                
                var lastUpdateTime = 0L
                downloadFileStreaming(url, file) { bytesRead: Long, totalLength: Long ->
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastUpdateTime > 500) { // 500ms마다 한 번만 업데이트
                        lastUpdateTime = currentTime
                        val mbRead = bytesRead / (1024 * 1024)
                        val mbTotal = totalLength / (1024 * 1024)
                        val percent = if (totalLength > 0) (bytesRead * 100 / totalLength).toInt() else 0
                        
                        runOnUiThread {
                            resultTextView.text = "데이터 다운로드 중: $fileName\n" +
                                    "$mbRead MB / $mbTotal MB ($percent%)\n\n" +
                                    "※ 대용량 파일이므로 Wi-Fi 연결을 권장하며,\n완료될 때까지 앱을 닫지 마세요."
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("Download", "Error downloading $fileName", e)
                runOnUiThread {
                    resultTextView.text = "❌ 다운로드 실패 ($fileName):\n${e.message}\n인터넷 연결을 확인하세요."
                }
                if (file.exists()) file.delete()
                return false
            }
        }
        return true
    }

    fun startSTT(): String {
        val sampleRate = 16000

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return "Permission Denied"
        }

        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            sampleRate
        )

        val buffer = ShortArray(sampleRate)

        audioRecord.startRecording()
        audioRecord.read(buffer, 0, buffer.size)
        audioRecord.stop()

        val floatBuffer = FloatArray(buffer.size)
        for (i in buffer.indices) {
            floatBuffer[i] = buffer[i] / 32768.0f
        }

        return recognize(floatBuffer)
    }

    fun testWithWavFile(filename: String): String {
        return try {
            val inputStream = assets.open(filename)
            val bytes = inputStream.readBytes()
            inputStream.close()
            
            // 일반적인 WAV 파일의 헤더 길이는 44바이트입니다.
            val headerSize = 44
            val audioDataSize = bytes.size - headerSize
            
            val byteBuffer = java.nio.ByteBuffer.wrap(bytes, headerSize, audioDataSize)
            byteBuffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
            
            val floatBuffer = FloatArray(audioDataSize / 2)
            for (i in floatBuffer.indices) {
                floatBuffer[i] = byteBuffer.short / 32768.0f
            }
            
            recognize(floatBuffer)
        } catch (e: Exception) {
            e.printStackTrace()
            "Error reading WAV file: ${e.message}"
        }
    }

    /**
     * 번역된 중국어 텍스트를 음성으로 재생합니다.
     * Android 내장 TTS 엔진을 사용하며, 중국어(Simplified Chinese)로 발화합니다.
     */
    private fun speakChinese(text: String?) {
        if (text.isNullOrBlank()) {
            Toast.makeText(this, "재생할 번역 텍스트가 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!isTtsReady) {
            Toast.makeText(this, "TTS 엔진이 아직 준비되지 않았습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        Log.d("TTS", "음성 재생: $text")
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_translate_${System.currentTimeMillis()}")
    }

    /**
     * 번역 결과에서 순수 중국어 텍스트만 추출합니다.
     * TranslationEngine은 "中国 (쭝궈)" 형태로 한글 발음을 괄호 안에 포함하여 반환하는데,
     * TTS에는 중국어 원문만 전달해야 자연스러운 발음이 됩니다.
     */
    private fun extractChineseText(translatedText: String): String? {
        if (translatedText.isBlank()) return null
        // 괄호와 그 안의 내용 제거: "你好 (니하오)" → "你好"
        val cleaned = translatedText.replace(Regex("\\s*\\([^)]*\\)"), "").trim()
        return if (cleaned.isNotBlank()) cleaned else null
    }

    companion object {
        init {
            System.loadLibrary("shr-onnxrtm") // Sherpa-onnx 전용 패치 라이브러리 (19.3MB, C-API 및 VERNEED 해결)
            System.loadLibrary("onnxruntime")     // ONNX 모바일 JNI (Microsoft AAR, TranslationEngine용)
            System.loadLibrary("onnxruntime4j_jni") 
            try {
                System.loadLibrary("sherpa-onnx-c-api")
            } catch (e: UnsatisfiedLinkError) {
                Log.e("STT_App", "sherpa-onnx-c-api 못불러옴", e)
            }
            System.loadLibrary("stt")
        }
    }
}
