package com.example.stt

import android.util.JsonReader
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader

/**
 * 순수 Kotlin BPE 토크나이저 (NLLB-200용)
 * DJL SentencePiece 대체 - Android 네이티브 API만 사용
 *
 * tokenizer.json (HuggingFace Tokenizers 형식)에서 vocab + merges를 로드하여
 * BPE encode/decode를 수행합니다.
 */
class NllbTokenizer : AutoCloseable {

    private var tokenToId: HashMap<String, Int> = HashMap()
    private var idToToken: ArrayList<String> = ArrayList()
    private var mergeRanks: HashMap<String, Int> = HashMap()
    private var isLoaded = false

    companion object {
        private const val TAG = "NllbTokenizer"
        // SentencePiece 공백 마커
        private const val SP_SPACE = "▁"  // U+2581
    }

    /**
     * tokenizer.json 파일을 스트리밍 파싱하여 vocab과 merges를 로드합니다.
     */
    fun load(tokenizerJsonPath: String): Boolean {
        val file = File(tokenizerJsonPath)
        if (!file.exists()) {
            Log.e(TAG, "tokenizer.json 파일이 존재하지 않습니다: $tokenizerJsonPath")
            return false
        }

        try {
            Log.d(TAG, "tokenizer.json 파싱 시작... (${file.length() / 1024 / 1024}MB)")
            val startTime = System.currentTimeMillis()

            FileInputStream(file).use { fis ->
                InputStreamReader(fis, "UTF-8").use { isr ->
                    JsonReader(isr).use { reader ->
                        parseTokenizerJson(reader)
                    }
                }
            }

            val elapsed = System.currentTimeMillis() - startTime
            Log.d(TAG, "tokenizer.json 파싱 완료: vocab=${tokenToId.size}, merges=${mergeRanks.size}, ${elapsed}ms")
            isLoaded = true
            return true
        } catch (e: Exception) {
            Log.e(TAG, "tokenizer.json 파싱 실패", e)
            return false
        }
    }

    /**
     * 최상위 JSON 객체를 파싱하면서 "model" 필드만 처리합니다.
     */
    private fun parseTokenizerJson(reader: JsonReader) {
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            if (name == "model") {
                parseModel(reader)
            } else {
                reader.skipValue()
            }
        }
        reader.endObject()
    }

    /**
     * "model" 객체에서 "vocab"과 "merges"를 추출합니다.
     */
    private fun parseModel(reader: JsonReader) {
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "vocab" -> parseVocab(reader)
                "merges" -> parseMerges(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
    }

    /**
     * vocab 객체를 스트리밍 파싱합니다. { "token": id, ... }
     */
    private fun parseVocab(reader: JsonReader) {
        // 예상 크기로 HashMap 초기화 (NLLB-200은 ~256K 토큰)
        tokenToId = HashMap(300000)
        reader.beginObject()
        while (reader.hasNext()) {
            val token = reader.nextName()
            val id = reader.nextInt()
            tokenToId[token] = id
        }
        reader.endObject()

        // idToToken 역매핑 구축
        idToToken = ArrayList(tokenToId.size)
        // 크기에 맞게 빈 슬롯 채우기
        val maxId = tokenToId.values.maxOrNull() ?: 0
        for (i in 0..maxId) {
            idToToken.add("")
        }
        for ((token, id) in tokenToId) {
            if (id < idToToken.size) {
                idToToken[id] = token
            }
        }
        Log.d(TAG, "Vocab 로드 완료: ${tokenToId.size}개 토큰")
    }

    /**
     * merges 배열을 스트리밍 파싱합니다. ["▁ t", "e r", ...]
     */
    private fun parseMerges(reader: JsonReader) {
        mergeRanks = HashMap(300000)
        var rank = 0
        reader.beginArray()
        while (reader.hasNext()) {
            val merge = if (reader.peek() == android.util.JsonToken.BEGIN_ARRAY) {
                reader.beginArray()
                val t1 = reader.nextString()
                val t2 = reader.nextString()
                while (reader.hasNext()) {
                    reader.skipValue() // 남은 항목 스킵
                }
                reader.endArray()
                "$t1 $t2"
            } else {
                reader.nextString()
            }
            mergeRanks[merge] = rank
            rank++
        }
        reader.endArray()
        Log.d(TAG, "Merges 로드 완료: ${mergeRanks.size}개 규칙")
    }

    /**
     * 텍스트를 토큰 ID 배열로 인코딩합니다. (BPE 알고리즘)
     */
    fun encode(text: String): IntArray {
        if (!isLoaded) {
            Log.e(TAG, "토크나이저가 로드되지 않았습니다")
            return IntArray(0)
        }

        // 1. 전처리: 공백을 ▁로 변환 (SentencePiece 방식)
        val processed = SP_SPACE + text.replace(" ", SP_SPACE)

        // 2. 단어 단위 분할 (▁를 기준으로 단어 경계 식별)
        val words = splitIntoWords(processed)

        // 3. 각 단어에 BPE 적용
        val allTokenIds = mutableListOf<Int>()
        for (word in words) {
            val bpeTokens = applyBpe(word)
            for (token in bpeTokens) {
                val id = tokenToId[token]
                if (id != null) {
                    allTokenIds.add(id)
                } else {
                    // 알 수 없는 토큰 → <unk> (ID=3)
                    Log.w(TAG, "Unknown token: '$token' → <unk>")
                    allTokenIds.add(3)
                }
            }
        }

        return allTokenIds.toIntArray()
    }

    /**
     * 토큰 ID 배열을 텍스트로 디코딩합니다.
     */
    fun decode(ids: IntArray): String {
        if (!isLoaded) {
            Log.e(TAG, "토크나이저가 로드되지 않았습니다")
            return ""
        }

        val sb = StringBuilder()
        for (id in ids) {
            if (id >= 0 && id < idToToken.size) {
                sb.append(idToToken[id])
            }
        }

        // ▁를 공백으로 변환하고 앞뒤 공백 제거
        return sb.toString()
            .replace(SP_SPACE, " ")
            .trim()
    }

    /**
     * 텍스트를 ▁ 기준으로 "단어" 단위로 분할합니다.
     * 각 단어는 ▁로 시작할 수 있습니다.
     */
    private fun splitIntoWords(text: String): List<String> {
        if (text.isEmpty()) return emptyList()

        val words = mutableListOf<String>()
        val sb = StringBuilder()

        for (i in text.indices) {
            val c = text[i]
            if (c == '▁' && sb.isNotEmpty()) {
                words.add(sb.toString())
                sb.clear()
            }
            sb.append(c)
        }
        if (sb.isNotEmpty()) {
            words.add(sb.toString())
        }

        return words
    }

    /**
     * 단어에 BPE(Byte Pair Encoding) 알고리즘을 적용합니다.
     *
     * 1. 단어를 개별 문자로 분할
     * 2. 인접한 쌍 중 merge rank가 가장 낮은(우선순위 높은) 쌍을 반복적으로 병합
     * 3. 더 이상 병합할 쌍이 없으면 결과 반환
     */
    private fun applyBpe(word: String): List<String> {
        if (word.isEmpty()) return emptyList()

        // 초기: 개별 문자(Unicode codepoint) 단위로 분할
        // 단, ▁는 다음 문자와 결합하여 처리
        val parts = splitIntoBpeUnits(word)
        if (parts.size <= 1) return parts

        val tokens = parts.toMutableList()

        while (tokens.size > 1) {
            // 모든 인접 쌍의 merge rank 확인
            var bestRank = Int.MAX_VALUE
            var bestIdx = -1

            for (i in 0 until tokens.size - 1) {
                val pair = "${tokens[i]} ${tokens[i + 1]}"
                val rank = mergeRanks[pair]
                if (rank != null && rank < bestRank) {
                    bestRank = rank
                    bestIdx = i
                }
            }

            // 병합 가능한 쌍이 없으면 종료
            if (bestIdx == -1) break

            // 최우선 쌍 병합
            val merged = tokens[bestIdx] + tokens[bestIdx + 1]
            tokens[bestIdx] = merged
            tokens.removeAt(bestIdx + 1)
        }

        return tokens
    }

    /**
     * 단어를 BPE 초기 유닛으로 분할합니다.
     * Unicode 문자 단위로 분할하되, ▁는 독립 유닛으로 처리합니다.
     */
    private fun splitIntoBpeUnits(word: String): MutableList<String> {
        val units = mutableListOf<String>()
        var i = 0
        while (i < word.length) {
            val cp = word.codePointAt(i)
            val charStr = String(Character.toChars(cp))
            units.add(charStr)
            i += Character.charCount(cp)
        }
        return units
    }

    override fun close() {
        tokenToId.clear()
        idToToken.clear()
        mergeRanks.clear()
        isLoaded = false
    }
}
