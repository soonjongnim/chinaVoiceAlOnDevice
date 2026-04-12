package com.example.stt

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * 대용량 모델 파일을 위한 스트리밍 다운로드 유틸리티
 */
fun downloadFileStreaming(
    url: String, 
    destFile: File, 
    onProgress: ((Long, Long) -> Unit)? = null
) {
    val client = OkHttpClient.Builder()
        .connectTimeout(1, java.util.concurrent.TimeUnit.MINUTES)
        .readTimeout(10, java.util.concurrent.TimeUnit.MINUTES)
        .build()

    val existingSize = if (destFile.exists()) destFile.length() else 0L

    val request = Request.Builder()
        .url(url)
        .apply {
            if (existingSize > 0) {
                header("Range", "bytes=$existingSize-")
            }
        }
        .build()
    
    client.newCall(request).execute().use { response ->
        // 200 (성공) 또는 206 (부분 성공 - 이어받기) 처리
        if (!response.isSuccessful) {
            // 416 Range Not Satisfiable: 이미 파일이 다 받아졌을 때 발생할 수 있음
            if (response.code == 416) return 
            
            throw Exception("다운로드 실패: ${response.code}")
        }

        val body = response.body ?: throw Exception("응답 바디가 비어있습니다.")
        
        // 이어받기 중이면 body.contentLength()는 '남은' 크기임
        val totalLength = if (response.code == 206) {
            existingSize + body.contentLength()
        } else {
            body.contentLength()
        }
        
        body.byteStream().use { input ->
            // append = true로 설정하여 파일 끝에 이어 붙임
            FileOutputStream(destFile, response.code == 206).use { fos ->
                val output = BufferedOutputStream(fos)
                val buffer = ByteArray(64 * 1024)
                var bytesRead: Int
                var totalBytesRead = existingSize

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead
                    
                    if (onProgress != null) {
                        onProgress(totalBytesRead, totalLength)
                    }
                }
                output.flush()
            }
        }
    }
}
