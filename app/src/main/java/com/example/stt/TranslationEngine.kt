package com.example.stt

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import java.io.File
import java.nio.LongBuffer

class TranslationEngine {
    private var env: OrtEnvironment? = null
    private var encoderSession: OrtSession? = null
    private var decoderSession: OrtSession? = null
    private var tokenizer: NllbTokenizer? = null

    // NLLB 기본 특수 토큰 ID
    private val eosTokenId: Long = 2
    private val padTokenId: Long = 1
    private val zhoHansTokenId: Long = 256200 // zho_Hans (중국어 간체) 타겟 언어 ID
    
    private var isInitialized = false

    fun initTranslation(modelDir: String): Boolean {
        if (isInitialized) {
            Log.d("TranslationEngine", "번역 엔진이 이미 초기화되어 있습니다. 기존 세션을 재사용합니다.")
            return true
        }
        try {
            Log.d("TranslationEngine", "ONNX 온디바이스 수동 번역 엔진 초기화 시작...")
            env = OrtEnvironment.getEnvironment()
            
            val sessionOptions = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(4)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            }

            val encoderPath = File(modelDir, "encoder_model_int8.onnx").absolutePath
            val decoderPath = File(modelDir, "decoder_model_int8.onnx").absolutePath
            val tokenizerPath = File(modelDir, "tokenizer.json").absolutePath

            if (!File(encoderPath).exists() || !File(decoderPath).exists() || !File(tokenizerPath).exists()) {
                Log.e("TranslationEngine", "필수 파일 누락! encoder=${File(encoderPath).exists()}, decoder=${File(decoderPath).exists()}, tokenizer=${File(tokenizerPath).exists()}")
                return false
            }

            // 순수 Kotlin BPE 토크나이저 로드
            Log.d("TranslationEngine", "NllbTokenizer 로딩 중...")
            tokenizer = NllbTokenizer()
            if (!tokenizer!!.load(tokenizerPath)) {
                Log.e("TranslationEngine", "토크나이저 로드 실패!")
                return false
            }

            Log.d("TranslationEngine", "Encoder 세션 로딩 중...")
            encoderSession = env?.createSession(encoderPath, sessionOptions)
            Log.d("TranslationEngine", "Decoder 세션 로딩 중...")
            decoderSession = env?.createSession(decoderPath, sessionOptions)
            
            isInitialized = true
            Log.d("TranslationEngine", "ONNX 온디바이스 세션 로드 성공!")
            return true
        } catch (e: Exception) {
            Log.e("TranslationEngine", "초기화 실패", e)
            return false
        }
    }

    fun translate(text: String): String {
        val currentEnv = env ?: return "Translator not initialized"
        val encSession = encoderSession ?: return "Encoder not initialized"
        val decSession = decoderSession ?: return "Decoder not initialized"
        val tok = tokenizer ?: return "Tokenizer not initialized"

        try {
            Log.d("TranslationEngine", "토큰화 시작: '$text'")
            
            // BPE 토크나이저로 인코딩
            val inputIdsIntArray = tok.encode(text)
            Log.d("TranslationEngine", "토큰화 결과: ${inputIdsIntArray.size}개 토큰")
            
            // NLLB-200 입력 형식: text_tokens + </s> (eos)
            val seqLen = inputIdsIntArray.size + 1 // +1 for EOS
            val inputIdsArray = LongArray(seqLen)
            for (i in inputIdsIntArray.indices) {
                inputIdsArray[i] = inputIdsIntArray[i].toLong()
            }
            inputIdsArray[seqLen - 1] = eosTokenId // EOS 토큰 추가
            
            val attentionMaskArray = LongArray(seqLen) { 1L }

            // 인코더 입력 텐서 생성
            val inputIdsTensor = OnnxTensor.createTensor(currentEnv, LongBuffer.wrap(inputIdsArray), longArrayOf(1, seqLen.toLong()))
            val attentionMaskTensor = OnnxTensor.createTensor(currentEnv, LongBuffer.wrap(attentionMaskArray), longArrayOf(1, seqLen.toLong()))

            Log.d("TranslationEngine", "인코더 실행 중...")
            val encInputs = mapOf(
                "input_ids" to inputIdsTensor,
                "attention_mask" to attentionMaskTensor
            )
            val encOutputs = encSession.run(encInputs)
            val lastHiddenState = encOutputs.get("last_hidden_state").get() as OnnxTensor

            // 디코더 탐욕 탐색(Greedy Search) 루프
            Log.d("TranslationEngine", "디코더 Greedy Search 루프 시작...")
            // NLLB-200 디코더 시작: </s> + tgt_lang_token
            val generatedTokens = mutableListOf<Long>(eosTokenId, zhoHansTokenId)
            
            val maxLen = 60
            for (step in 0 until maxLen) {
                val decSeqLen = generatedTokens.size.toLong()
                val decInputArray = generatedTokens.toLongArray()
                val decInputTensor = OnnxTensor.createTensor(currentEnv, LongBuffer.wrap(decInputArray), longArrayOf(1, decSeqLen))
                
                val decInputs = mapOf(
                    "input_ids" to decInputTensor,
                    "encoder_hidden_states" to lastHiddenState,
                    "encoder_attention_mask" to attentionMaskTensor
                )
                
                val decOutputs = decSession.run(decInputs)
                val logitsTensor = decOutputs.get("logits").get() as OnnxTensor
                
                val floatBuffer = logitsTensor.floatBuffer
                val vocabSize = logitsTensor.info.shape[2].toInt()
                
                var maxLogit = Float.NEGATIVE_INFINITY
                var maxIdx = 0L
                
                val offset = (decSeqLen.toInt() - 1) * vocabSize
                for (i in 0 until vocabSize) {
                    val logit = floatBuffer.get(offset + i)
                    if (logit > maxLogit) {
                        maxLogit = logit
                        maxIdx = i.toLong()
                    }
                }
                
                decOutputs.close()
                decInputTensor.close()
                
                if (maxIdx == eosTokenId || maxIdx == padTokenId) {
                    break
                }
                
                generatedTokens.add(maxIdx)
            }
            
            encOutputs.close()
            inputIdsTensor.close()
            attentionMaskTensor.close()
            
            Log.d("TranslationEngine", "디코더 루프 종료. 토큰 역변환 시작... (${generatedTokens.size}개 토큰)")
            // </s> + tgt_lang_token 제거 후 디코딩
            val resultIds = generatedTokens.drop(2).map { it.toInt() }.toIntArray()
            val rawResult = tok.decode(resultIds)
            Log.d("TranslationEngine", "번역 결과: '$rawResult'")
            
            // 중국어일 경우 한글 발음 기호 괄호 추가
            val resultWithPronunciation = appendKoreanPronunciation(rawResult)
            
            return resultWithPronunciation

        } catch (e: Exception) {
            Log.e("TranslationEngine", "번역 추론 에러 발생", e)
            return "번역 오류: ${e.message}"
        }
    }

    private fun appendKoreanPronunciation(text: String): String {
        // 안드로이드 10 (Q, API 29) 이상에서만 ICU 제공
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return text
        
        // 중국어 한자가 있는지 확인 (간단한 범위 체크)
        val hasChinese = text.any { it.code in 0x4E00..0x9FFF }
        if (!hasChinese) return text

        try {
            // 한자를 병음(Pinyin) 성조 제거 형태로 변환
            val transliterator = android.icu.text.Transliterator.getInstance("Han-Latin; NFD; [:Nonspacing Mark:] Remove; NFC")
            val pinyinStr = transliterator.transliterate(text)

            // 병음을 소문자로 변환한 뒤 단어 단위로 쪼개서 한글 발음으로 대체
            val builder = StringBuilder()
            var currentPinyin = StringBuilder()
            
            for (char in pinyinStr) {
                if (char.isLetter()) {
                    currentPinyin.append(char.lowercaseChar())
                } else {
                    if (currentPinyin.isNotEmpty()) {
                        val py = currentPinyin.toString()
                        builder.append(PinyinMapper.map[py] ?: py)
                        currentPinyin.clear()
                    }
                    builder.append(char)
                }
            }
            if (currentPinyin.isNotEmpty()) {
                val py = currentPinyin.toString()
                builder.append(PinyinMapper.map[py] ?: py)
            }
            
            val hangulPronunciation = builder.toString().trim()
            
            // 원래 문장과 한글 발음을 조합하여 리턴
            return "$text ($hangulPronunciation)"
        } catch (e: Exception) {
            Log.w("TranslationEngine", "한글 발음 생성 실패", e)
            return text
        }
    }

    fun close() {
        encoderSession?.close()
        decoderSession?.close()
        env?.close()
        tokenizer?.close()
    }
}
