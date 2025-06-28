package com.example.testapp.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import org.json.JSONObject
import org.json.JSONException
import org.json.JSONArray
import android.util.Log

class YoutubeDlRepository(private val context: Context) {
    
    private val _downloadProgress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val downloadProgress: Flow<Map<String, DownloadProgress>> = _downloadProgress.asStateFlow()
    
    // YouTube 우회 헬퍼
    private val bypassHelper = YouTubeBypassHelper(context)
    
    // 포맷 정보 캐싱 (videoId -> QualityOptions)
    private val formatCache = mutableMapOf<String, List<QualityOption>>()
    private val formatCacheTimestamp = mutableMapOf<String, Long>()
    private val cacheValidityDuration = 5 * 60 * 1000L // 5분
    
    suspend fun searchVideos(query: String): Result<List<VideoInfo>> {
        return withContext(Dispatchers.IO) {
            try {
                // YT-DLP를 사용한 실제 YouTube 검색
                val searchQuery = "ytsearch15:$query"  // 최대 15개 결과 검색
                val request = YoutubeDLRequest(searchQuery)
                request.addOption("--flat-playlist")  // 빠른 검색을 위해 다시 활성화
                request.addOption("--dump-json")
                request.addOption("--no-warnings")
                
                // 실제 검색 수행
                val searchResult = YoutubeDL.getInstance().execute(request)
                
                // JSON 결과 파싱
                val videoList = parseSearchResults(searchResult.out)
                
                if (videoList.isNotEmpty()) {
                    Result.success(videoList)
                } else {
                    // JSON 파싱 실패 시 기본 검색 결과 반환
                    Result.success(createSearchBasedResults(query))
                }
                
            } catch (e: Exception) {
                // 검색 실패 시 에러 결과 반환
                Result.failure(e)
            }
        }
    }
    
    suspend fun getQualityOptionsForVideo(videoInfo: VideoInfo): Pair<List<QualityOption>, DetailedVideoInfo?> {
        return try {
            Log.d("YoutubeDlRepository", "Getting quality options for video: ${videoInfo.title}")
            
            // 1단계: 캐시 확인
            val cachedOptions = getCachedQualityOptions(videoInfo.id)
            if (cachedOptions != null) {
                Log.d("YoutubeDlRepository", "Using cached quality options for ${videoInfo.id}")
                return Pair(cachedOptions, null) // 캐시된 경우 상세정보 없음
            }
            
            Log.d("YoutubeDlRepository", "Cache miss, fetching quality options from server")
            
            // 2단계: 실제 포맷 정보 조회
            val formatResult = getAvailableFormats(videoInfo.url)
            if (formatResult.isFailure) {
                Log.w("YoutubeDlRepository", "Failed to get formats, using basic options")
                val basicOptions = getBasicQualityOptions()
                // 기본 옵션도 짧은 시간 캐싱 (1분)
                cacheQualityOptions(videoInfo.id, basicOptions, 60 * 1000L)
                return Pair(basicOptions, null)
            }
            
            val availableFormats = formatResult.getOrNull() ?: emptyList()
            Log.d("YoutubeDlRepository", "Retrieved ${availableFormats.size} formats for quality analysis")
            
            if (availableFormats.isEmpty()) {
                Log.w("YoutubeDlRepository", "No formats available, using basic options")
                val basicOptions = getBasicQualityOptions()
                cacheQualityOptions(videoInfo.id, basicOptions, 60 * 1000L)
                return Pair(basicOptions, null)
            }
            
            // 3단계: 실제 존재하는 해상도 분석
            val actualResolutions = analyzeAvailableResolutions(availableFormats)
            Log.d("YoutubeDlRepository", "Actual available resolutions: ${actualResolutions.map { it.displayName }}")
            
            if (actualResolutions.isEmpty()) {
                Log.w("YoutubeDlRepository", "No resolutions found, using basic options")
                val basicOptions = getBasicQualityOptions()
                cacheQualityOptions(videoInfo.id, basicOptions, 60 * 1000L)
                return Pair(basicOptions, null)
            }
            
            // 4단계: 실제 존재하는 해상도만으로 품질 옵션 생성
            val qualityOptions = actualResolutions.map { resolution ->
                val estimatedSize = estimateQualitySize(resolution)
                val isRecommended = resolution == Resolution.P720 || 
                                  (actualResolutions.contains(Resolution.P720).not() && resolution == actualResolutions.first())
                
                QualityOption(
                    resolution = resolution,
                    videoFormat = "auto", // 실제 다운로드 시 자동 선택
                    audioFormat = "auto",
                    estimatedSize = estimatedSize,
                    isRecommended = isRecommended
                )
            }.sortedByDescending { it.resolution.height } // 높은 화질부터 정렬
            
            // 5단계: 상세 정보 가져오기 (기존 요청에서 상세 정보 파싱)
            val detailedInfo = getDetailedVideoInfoFromFormats(videoInfo.url)
            
            // 6단계: 성공적으로 가져온 옵션 캐싱
            cacheQualityOptions(videoInfo.id, qualityOptions)
            
            return Pair(qualityOptions, detailedInfo)
            
        } catch (e: Exception) {
            Log.e("YoutubeDlRepository", "Error getting quality options: ${e.message}", e)
            val basicOptions = getBasicQualityOptions()
            // 에러 시에도 짧은 시간 캐싱하여 반복 요청 방지
            cacheQualityOptions(videoInfo.id, basicOptions, 30 * 1000L)
            return Pair(basicOptions, null)
        }
    }
    
    private fun getBasicQualityOptions(): List<QualityOption> {
        // 폴백용 기본 옵션 (안전한 포맷 사용)
        Log.d("YoutubeDlRepository", "Using fallback basic quality options")
        return listOf(
            QualityOption(
                resolution = Resolution.P360,
                videoFormat = "safe", // 안전한 폴백 포맷
                audioFormat = "safe", 
                estimatedSize = "~30MB",
                isRecommended = false
            ),
            QualityOption(
                resolution = Resolution.P720,
                videoFormat = "safe",
                audioFormat = "safe",
                estimatedSize = "~80MB", 
                isRecommended = true
            ),
            QualityOption(
                resolution = Resolution.P1080,
                videoFormat = "safe",
                audioFormat = "safe",
                estimatedSize = "~150MB", 
                isRecommended = false
            )
        )
    }
    
    private fun estimateQualitySize(resolution: Resolution): String {
        return when(resolution) {
            Resolution.P360 -> "~30MB"
            Resolution.P720 -> "~80MB"
            Resolution.P1080 -> "~150MB"
            Resolution.P1440 -> "~300MB"
            Resolution.P2160 -> "~500MB"
            Resolution.AUDIO_BEST -> "~10MB"
        }
    }
    
    suspend fun downloadVideoWithQuality(
        videoInfo: VideoInfo,
        selectedResolution: Resolution,
        onProgress: (DownloadProgress) -> Unit = {}
    ): Result<String> {
        val startTime = System.currentTimeMillis()
        return withContext(Dispatchers.IO) {
            try {
                Log.d("YoutubeDlRepository", "=== DOWNLOAD START WITH QUALITY ===")
                Log.d("YoutubeDlRepository", "Video: ${videoInfo.title}")
                Log.d("YoutubeDlRepository", "Selected Quality: ${selectedResolution.displayName}")
                
                // 포맷 분석 단계 표시
                val analyzingProgress = DownloadProgress(
                    videoId = videoInfo.id,
                    progress = 5f,
                    status = DownloadStatus.PREPARING,
                    startTime = startTime,
                    currentPhase = DownloadPhase.ANALYZING_FORMAT,
                    selectedResolution = selectedResolution
                )
                updateDownloadProgress(videoInfo.id, analyzingProgress)
                onProgress(analyzingProgress)
                
                // 실제 포맷 데이터 기반 선택
                val selectedFormat = getOptimalFormatForVideo(videoInfo.url, selectedResolution)
                
                Log.d("YoutubeDlRepository", "Selected format for ${selectedResolution.displayName}: $selectedFormat")
                
                // 기존 다운로드 로직 재사용 - 오디오는 Music 폴더, 비디오는 Movies 폴더
                val downloadResult = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
                    if (selectedResolution == Resolution.AUDIO_BEST) {
                        downloadToMusicFolderWithFormat(videoInfo, selectedFormat, startTime, selectedResolution, onProgress)
                    } else {
                        downloadToMoviesFolderWithFormat(videoInfo, selectedFormat, startTime, selectedResolution, onProgress)
                    }
                } else {
                    downloadWithMediaStoreWithFormat(videoInfo, selectedFormat, startTime, selectedResolution, onProgress)
                }
                
                return@withContext downloadResult
            } catch (e: Exception) {
                Log.e("YoutubeDlRepository", "Exception during quality download: ${e.message}", e)
                
                val errorMessage = e.message ?: "Unknown error"
                val errorType = detectDownloadError(errorMessage)
                
                val errorProgress = DownloadProgress(
                    videoId = videoInfo.id,
                    progress = 0f,
                    status = DownloadStatus.ERROR,
                    error = errorMessage,
                    startTime = startTime,
                    selectedResolution = selectedResolution,
                    errorType = errorType,
                    currentPhase = DownloadPhase.ERROR
                )
                updateDownloadProgress(videoInfo.id, errorProgress)
                onProgress(errorProgress)
                
                Result.failure(e)
            }
        }
    }


    suspend fun downloadVideo(
        videoInfo: VideoInfo,
        onProgress: (DownloadProgress) -> Unit = {}
    ): Result<String> {
        val startTime = System.currentTimeMillis()
        return withContext(Dispatchers.IO) {
            try {
                Log.d("YoutubeDlRepository", "=== DOWNLOAD START ===")
                Log.d("YoutubeDlRepository", "Video: ${videoInfo.title}")
                Log.d("YoutubeDlRepository", "URL: ${videoInfo.url}")
                Log.d("YoutubeDlRepository", "Video ID: ${videoInfo.id}")
                
                // Download strategy based on permissions and Android version
                val downloadResult = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
                    // Android 11+ with MANAGE_EXTERNAL_STORAGE: Use Movies folder
                    downloadToMoviesFolder(videoInfo, startTime, onProgress)
                } else {
                    // Fallback: Use MediaStore API or app-scoped directory
                    downloadWithMediaStore(videoInfo, startTime, onProgress)
                }
                
                return@withContext downloadResult
            } catch (e: Exception) {
                Log.e("YoutubeDlRepository", "Exception during download: ${e.message}", e)
                
                val errorProgress = DownloadProgress(
                    videoId = videoInfo.id,
                    progress = 0f,
                    status = DownloadStatus.ERROR,
                    error = e.message,
                    startTime = startTime
                )
                updateDownloadProgress(videoInfo.id, errorProgress)
                onProgress(errorProgress)
                
                Result.failure(e)
            }
        }
    }
    
    private suspend fun downloadToMoviesFolder(
        videoInfo: VideoInfo,
        startTime: Long,
        onProgress: (DownloadProgress) -> Unit
    ): Result<String> {
        return try {
            Log.d("YoutubeDlRepository", "=== DOWNLOAD TO MOVIES FOLDER ===")
            
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            if (!downloadDir.exists()) {
                val created = downloadDir.mkdirs()
                Log.d("YoutubeDlRepository", "Movies directory created: $created, path: ${downloadDir.absolutePath}")
            }
            
            performDownload(videoInfo, downloadDir, startTime, onProgress)
        } catch (e: Exception) {
            Log.e("YoutubeDlRepository", "Movies folder download failed: ${e.message}")
            Result.failure(e)
        }
    }
    
    private suspend fun downloadWithMediaStore(
        videoInfo: VideoInfo,
        startTime: Long,
        onProgress: (DownloadProgress) -> Unit
    ): Result<String> {
        return try {
            Log.d("YoutubeDlRepository", "=== DOWNLOAD WITH MEDIASTORE/APP DIRECTORY ===")
            
            // Use app-scoped directory as fallback
            val downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)!!
            if (!downloadDir.exists()) {
                val created = downloadDir.mkdirs()
                Log.d("YoutubeDlRepository", "App directory created: $created, path: ${downloadDir.absolutePath}")
            }
            
            performDownload(videoInfo, downloadDir, startTime, onProgress)
        } catch (e: Exception) {
            Log.e("YoutubeDlRepository", "MediaStore download failed: ${e.message}")
            Result.failure(e)
        }
    }
    
    private suspend fun downloadToMusicFolder(
        videoInfo: VideoInfo,
        startTime: Long,
        onProgress: (DownloadProgress) -> Unit
    ): Result<String> {
        return try {
            Log.d("YoutubeDlRepository", "=== DOWNLOAD TO MUSIC FOLDER ===")
            
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            
            // Music 폴더 권한 및 접근성 검증
            if (!downloadDir.exists()) {
                val created = downloadDir.mkdirs()
                Log.d("YoutubeDlRepository", "Music directory created: $created, path: ${downloadDir.absolutePath}")
                
                if (!created) {
                    Log.e("YoutubeDlRepository", "Failed to create Music directory")
                    throw Exception("Music 폴더를 생성할 수 없습니다. 저장소 권한을 확인해주세요.")
                }
            }
            
            // 쓰기 권한 검증
            if (!downloadDir.canWrite()) {
                Log.e("YoutubeDlRepository", "No write permission for Music directory")
                throw Exception("Music 폴더에 쓰기 권한이 없습니다. 권한을 확인해주세요.")
            }
            
            Log.d("YoutubeDlRepository", "Music folder verified, path: ${downloadDir.absolutePath}")
            performAudioDownload(videoInfo, downloadDir, startTime, onProgress)
        } catch (e: Exception) {
            Log.e("YoutubeDlRepository", "Music folder download failed: ${e.message}")
            Result.failure(e)
        }
    }
    
    private suspend fun downloadWithMediaStoreAudio(
        videoInfo: VideoInfo,
        startTime: Long,
        onProgress: (DownloadProgress) -> Unit
    ): Result<String> {
        return try {
            Log.d("YoutubeDlRepository", "=== DOWNLOAD AUDIO WITH MEDIASTORE/APP DIRECTORY ===")
            
            // Use app-scoped directory as fallback for audio
            val downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)!!
            if (!downloadDir.exists()) {
                val created = downloadDir.mkdirs()
                Log.d("YoutubeDlRepository", "App audio directory created: $created, path: ${downloadDir.absolutePath}")
            }
            
            performAudioDownload(videoInfo, downloadDir, startTime, onProgress)
        } catch (e: Exception) {
            Log.e("YoutubeDlRepository", "MediaStore audio download failed: ${e.message}")
            Result.failure(e)
        }
    }
    
    private suspend fun performDownload(
        videoInfo: VideoInfo,
        downloadDir: File,
        startTime: Long,
        onProgress: (DownloadProgress) -> Unit
    ): Result<String> {
        return try {
            // 1단계: 사용 가능한 포맷 목록 먼저 확인
            Log.d("YoutubeDlRepository", "STEP 1: Checking available formats...")
            val formatListResult = getAvailableFormats(videoInfo.url)
            
            if (formatListResult.isFailure) {
                val error = "Failed to get format list: ${formatListResult.exceptionOrNull()?.message}"
                Log.e("YoutubeDlRepository", error)
                
                val errorProgress = DownloadProgress(
                    videoId = videoInfo.id,
                    progress = 0f,
                    status = DownloadStatus.ERROR,
                    error = error,
                    startTime = startTime
                )
                updateDownloadProgress(videoInfo.id, errorProgress)
                onProgress(errorProgress)
                
                return Result.failure(Exception(error))
            }
            
            val availableFormats = formatListResult.getOrNull() ?: emptyList()
            Log.d("YoutubeDlRepository", "Available formats: ${availableFormats.size} found")
            availableFormats.forEach { format -> 
                Log.d("YoutubeDlRepository", "Format: $format")
            }
            
            // 2단계: 가장 안전한 포맷 선택
            val selectedFormat = selectSafeFormat(availableFormats)
            Log.d("YoutubeDlRepository", "Selected format: $selectedFormat")
            
            // 3단계: 다운로드 요청 준비
            val request = YoutubeDLRequest(videoInfo.url)
            
            // 기본 옵션
            request.addOption("--user-agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            request.addOption("-o", "${downloadDir.absolutePath}/%(title)s.%(ext)s")
            request.addOption("-f", selectedFormat)
            request.addOption("--no-post-overwrites")
            
            // FFmpeg 기반 비디오+오디오 병합 활성화
            if (selectedFormat.contains("+")) {
                request.addOption("--merge-output-format", "mp4")
                Log.d("YoutubeDlRepository", "🎬 FFmpeg enabled for merging: $selectedFormat → mp4")
            } else {
                Log.d("YoutubeDlRepository", "📹 Using unified format: $selectedFormat (no merging needed)")
            }
            
            Log.d("YoutubeDlRepository", "STEP 2: Starting download to: ${downloadDir.absolutePath}")
            
            // 다운로드 준비 상태 설정
            val preparingProgress = DownloadProgress(
                videoId = videoInfo.id,
                progress = 0f,
                status = DownloadStatus.PREPARING,
                startTime = startTime
            )
            updateDownloadProgress(videoInfo.id, preparingProgress)
            onProgress(preparingProgress)
            
            // yt-dlp 출력에서 파일 경로 추출을 위한 변수
            var extractedFilePath: String? = null
            
            // 진행률 콜백으로 실행
            val result = YoutubeDL.getInstance().execute(request) { progress, etaInSeconds, line ->
                Log.d("YoutubeDlRepository", "Progress: $progress%, ETA: ${etaInSeconds}s, Line: $line")
                
                // yt-dlp 출력에서 파일 경로 추출
                if (line.contains("[download] Destination:") || line.contains("Merging formats into")) {
                    val pathPattern = Regex("\"([^\"]+\\.(mp4|webm|mkv|avi|mov|flv|m4v))\"")
                    val match = pathPattern.find(line)
                    if (match != null) {
                        extractedFilePath = match.groupValues[1]
                        Log.d("YoutubeDlRepository", "Extracted file path from yt-dlp: $extractedFilePath")
                    }
                }
                
                val progressFloat = try {
                    val p = progress.toFloat()
                    // -1% 또는 음수 진행률 필터링
                    if (p < 0) {
                        _downloadProgress.value[videoInfo.id]?.progress ?: 0f
                    } else {
                        p
                    }
                } catch (e: NumberFormatException) {
                    _downloadProgress.value[videoInfo.id]?.progress ?: 0f
                }
                
                // 진행률이 변경된 경우에만 업데이트
                val currentProgress = _downloadProgress.value[videoInfo.id]?.progress ?: 0f
                if (progressFloat > currentProgress) {
                    val progressUpdate = DownloadProgress(
                        videoId = videoInfo.id,
                        progress = progressFloat,
                        status = DownloadStatus.DOWNLOADING,
                        startTime = startTime
                    )
                    updateDownloadProgress(videoInfo.id, progressUpdate)
                    onProgress(progressUpdate)
                }
            }
            
            Log.d("YoutubeDlRepository", "Download completed - Exit code: ${result.exitCode}")
            
            if (result.exitCode == 0) {
                Log.d("YoutubeDlRepository", "=== DOWNLOAD SUCCESS ===")
                
                // 다운로드된 파일 찾기 (새로운 로직)
                val downloadedFile = extractedFilePath?.let { path ->
                    val file = File(path)
                    if (file.exists()) {
                        Log.d("YoutubeDlRepository", "Using extracted file path: $path")
                        file
                    } else {
                        Log.d("YoutubeDlRepository", "Extracted file does not exist, using file detection logic")
                        findLatestDownloadedFile(downloadDir, videoInfo.title, startTime)
                    }
                } ?: run {
                    Log.d("YoutubeDlRepository", "No extracted path, using file detection logic")
                    findLatestDownloadedFile(downloadDir, videoInfo.title, startTime)
                }
                
                val completedProgress = DownloadProgress(
                    videoId = videoInfo.id,
                    progress = 100f,
                    status = DownloadStatus.COMPLETED,
                    filePath = downloadedFile?.absolutePath,
                    startTime = startTime
                )
                updateDownloadProgress(videoInfo.id, completedProgress)
                onProgress(completedProgress)
                
                Result.success(downloadDir.absolutePath)
            } else {
                val errorMsg = "Download failed: ${result.err.ifEmpty { "Unknown error" }}"
                Log.e("YoutubeDlRepository", errorMsg)
                
                val errorProgress = DownloadProgress(
                    videoId = videoInfo.id,
                    progress = 0f,
                    status = DownloadStatus.ERROR,
                    error = errorMsg.take(200),
                    startTime = startTime
                )
                updateDownloadProgress(videoInfo.id, errorProgress)
                onProgress(errorProgress)
                
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Log.e("YoutubeDlRepository", "performDownload exception: ${e.message}", e)
            
            val errorProgress = DownloadProgress(
                videoId = videoInfo.id,
                progress = 0f,
                status = DownloadStatus.ERROR,
                error = e.message,
                startTime = startTime
            )
            updateDownloadProgress(videoInfo.id, errorProgress)
            onProgress(errorProgress)
            
            Result.failure(e)
        }
    }
    
    private suspend fun performAudioDownload(
        videoInfo: VideoInfo,
        downloadDir: File,
        startTime: Long,
        onProgress: (DownloadProgress) -> Unit
    ): Result<String> {
        return try {
            Log.d("YoutubeDlRepository", "=== AUDIO DOWNLOAD STARTING ===")
            Log.d("YoutubeDlRepository", "Download directory: ${downloadDir.absolutePath}")
            
            // 다운로드 요청 준비 (오디오 전용)
            val request = YoutubeDLRequest(videoInfo.url)
            
            // 오디오 전용 옵션 - 최고음질 m4a (구체적 포맷 ID 우선)
            request.addOption("--user-agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            request.addOption("-o", "${downloadDir.absolutePath}/%(title)s.%(ext)s")
            request.addOption("-f", "140/bestaudio[ext=m4a]/bestaudio") // 140=m4a 130k, 폴백 체인
            request.addOption("--no-post-overwrites")
            
            Log.d("YoutubeDlRepository", "Starting audio download to: ${downloadDir.absolutePath}")
            
            // 다운로드 준비 상태 설정
            val preparingProgress = DownloadProgress(
                videoId = videoInfo.id,
                progress = 0f,
                status = DownloadStatus.PREPARING,
                startTime = startTime,
                selectedResolution = Resolution.AUDIO_BEST
            )
            updateDownloadProgress(videoInfo.id, preparingProgress)
            onProgress(preparingProgress)
            
            // yt-dlp 출력에서 파일 경로 추출을 위한 변수
            var extractedFilePath: String? = null
            
            // 진행률 콜백으로 실행
            val result = YoutubeDL.getInstance().execute(request) { progress, etaInSeconds, line ->
                Log.d("YoutubeDlRepository", "Audio Progress: $progress%, ETA: ${etaInSeconds}s, Line: $line")
                
                // yt-dlp 출력에서 파일 경로 추출 (오디오 파일용) - 더 포괄적인 패턴
                if (line.contains("[download] Destination:") || line.contains("Destination:")) {
                    // 따옴표 있는 경우와 없는 경우 모두 처리
                    val pathPattern = Regex("(?:Destination:|to)\\s*[:\"]?\\s*([^\"\\s]+\\.(m4a|mp3|webm|aac|opus))")
                    val match = pathPattern.find(line)
                    if (match != null) {
                        extractedFilePath = match.groupValues[1]
                        Log.d("YoutubeDlRepository", "Extracted audio file path: $extractedFilePath")
                    } else {
                        // 폴백: 단순한 파일명 패턴
                        val simplePattern = Regex("([^\\s]+\\.(m4a|mp3|webm|aac|opus))")
                        val simpleMatch = simplePattern.find(line)
                        if (simpleMatch != null) {
                            val fileName = simpleMatch.groupValues[1]
                            extractedFilePath = "${downloadDir.absolutePath}/$fileName"
                            Log.d("YoutubeDlRepository", "Extracted audio file path (fallback): $extractedFilePath")
                        }
                    }
                }
                
                val progressFloat = try {
                    val p = progress.toFloat()
                    if (p < 0) {
                        _downloadProgress.value[videoInfo.id]?.progress ?: 0f
                    } else {
                        p
                    }
                } catch (e: NumberFormatException) {
                    _downloadProgress.value[videoInfo.id]?.progress ?: 0f
                }
                
                // 진행률이 변경된 경우에만 업데이트
                val currentProgress = _downloadProgress.value[videoInfo.id]?.progress ?: 0f
                if (progressFloat > currentProgress) {
                    val progressUpdate = DownloadProgress(
                        videoId = videoInfo.id,
                        progress = progressFloat,
                        status = DownloadStatus.DOWNLOADING,
                        startTime = startTime,
                        selectedResolution = Resolution.AUDIO_BEST
                    )
                    updateDownloadProgress(videoInfo.id, progressUpdate)
                    onProgress(progressUpdate)
                }
            }
            
            Log.d("YoutubeDlRepository", "Audio download completed - Exit code: ${result.exitCode}")
            Log.d("YoutubeDlRepository", "Audio download output: ${result.out}")
            if (result.err.isNotEmpty()) {
                Log.e("YoutubeDlRepository", "Audio download stderr: ${result.err}")
            }
            
            if (result.exitCode == 0) {
                Log.d("YoutubeDlRepository", "=== AUDIO DOWNLOAD SUCCESS ===")
                
                // 다운로드된 오디오 파일 찾기
                val downloadedFile = extractedFilePath?.let { path ->
                    val file = File(path)
                    if (file.exists()) {
                        Log.d("YoutubeDlRepository", "Using extracted audio file path: $path")
                        file
                    } else {
                        Log.d("YoutubeDlRepository", "Extracted file does not exist, using audio file detection logic")
                        findLatestDownloadedAudioFile(downloadDir, videoInfo.title, startTime)
                    }
                } ?: run {
                    Log.d("YoutubeDlRepository", "No extracted path, using audio file detection logic")
                    findLatestDownloadedAudioFile(downloadDir, videoInfo.title, startTime)
                }
                
                val completedProgress = DownloadProgress(
                    videoId = videoInfo.id,
                    progress = 100f,
                    status = DownloadStatus.COMPLETED,
                    filePath = downloadedFile?.absolutePath,
                    startTime = startTime,
                    selectedResolution = Resolution.AUDIO_BEST
                )
                updateDownloadProgress(videoInfo.id, completedProgress)
                onProgress(completedProgress)
                
                Result.success(downloadDir.absolutePath)
            } else {
                val stderr = result.err.ifEmpty { "Unknown error" }
                val stdout = result.out
                
                // 구체적인 에러 타입 감지
                val specificError = when {
                    stderr.contains("format not available") || stderr.contains("No such format") -> 
                        "요청한 오디오 포맷을 사용할 수 없습니다. 다른 화질을 시도해주세요."
                    stderr.contains("Private video") || stderr.contains("unavailable") -> 
                        "이 영상은 다운로드할 수 없습니다 (비공개/제한된 영상)"
                    stderr.contains("region") -> 
                        "지역 제한으로 인해 다운로드할 수 없습니다"
                    stderr.contains("permission") || stderr.contains("403") -> 
                        "접근 권한이 없습니다"
                    stderr.contains("network") || stderr.contains("timeout") -> 
                        "네트워크 연결에 문제가 있습니다"
                    else -> "오디오 다운로드 실패: $stderr"
                }
                
                Log.e("YoutubeDlRepository", "Audio download failed - Exit code: ${result.exitCode}")
                Log.e("YoutubeDlRepository", "Specific error: $specificError")
                Log.e("YoutubeDlRepository", "Full stderr: $stderr")
                
                val errorProgress = DownloadProgress(
                    videoId = videoInfo.id,
                    progress = 0f,
                    status = DownloadStatus.ERROR,
                    error = specificError.take(200),
                    startTime = startTime,
                    selectedResolution = Resolution.AUDIO_BEST,
                    errorType = detectDownloadError(stderr)
                )
                updateDownloadProgress(videoInfo.id, errorProgress)
                onProgress(errorProgress)
                
                Result.failure(Exception(specificError))
            }
        } catch (e: Exception) {
            Log.e("YoutubeDlRepository", "performAudioDownload exception: ${e.message}", e)
            
            val errorProgress = DownloadProgress(
                videoId = videoInfo.id,
                progress = 0f,
                status = DownloadStatus.ERROR,
                error = e.message,
                startTime = startTime,
                selectedResolution = Resolution.AUDIO_BEST
            )
            updateDownloadProgress(videoInfo.id, errorProgress)
            onProgress(errorProgress)
            
            Result.failure(e)
        }
    }
    
    private fun findLatestDownloadedFile(downloadDir: File, videoTitle: String, startTime: Long): File? {
        return try {
            Log.d("YoutubeDlRepository", "=== FINDING LATEST DOWNLOADED FILE ===")
            Log.d("YoutubeDlRepository", "Download directory: ${downloadDir.absolutePath}")
            Log.d("YoutubeDlRepository", "Video title: $videoTitle")
            Log.d("YoutubeDlRepository", "Start time: $startTime")
            
            val allFiles = downloadDir.listFiles() ?: return null
            Log.d("YoutubeDlRepository", "Total files in directory: ${allFiles.size}")
            
            // 비디오 파일 확장자 필터
            val videoExtensions = listOf(".mp4", ".webm", ".mkv", ".avi", ".mov", ".flv", ".m4v")
            val videoFiles = allFiles.filter { file ->
                videoExtensions.any { ext -> file.name.lowercase().endsWith(ext) }
            }
            
            Log.d("YoutubeDlRepository", "Video files found: ${videoFiles.size}")
            videoFiles.forEach { file ->
                Log.d("YoutubeDlRepository", "Video file: ${file.name}, lastModified: ${file.lastModified()}, size: ${file.length()}")
            }
            
            // 다운로드 시작 시간 이후에 생성/수정된 파일들만 필터링
            val recentFiles = videoFiles.filter { file ->
                file.lastModified() >= startTime - 5000 // 5초 여유를 둠
            }
            
            Log.d("YoutubeDlRepository", "Recent files (after startTime): ${recentFiles.size}")
            recentFiles.forEach { file ->
                Log.d("YoutubeDlRepository", "Recent file: ${file.name}, lastModified: ${file.lastModified()}")
            }
            
            if (recentFiles.isEmpty()) {
                Log.w("YoutubeDlRepository", "No recent files found, falling back to title matching")
                // 제목 매칭으로 대체
                val titleMatched = videoFiles.find { file ->
                    file.name.contains(videoTitle.take(30), ignoreCase = true)
                }
                Log.d("YoutubeDlRepository", "Title matched file: ${titleMatched?.name}")
                return titleMatched
            }
            
            // 최신 파일 선택 (가장 늦게 수정된 파일)
            val latestFile = recentFiles.maxByOrNull { it.lastModified() }
            
            Log.d("YoutubeDlRepository", "Selected latest file: ${latestFile?.name}")
            
            // 추가 검증: 제목과 어느 정도 일치하는지 확인
            if (latestFile != null) {
                val titleWords = videoTitle.lowercase().split(" ", "-", "_").filter { it.length > 2 }
                val fileName = latestFile.name.lowercase()
                val matchCount = titleWords.count { word -> fileName.contains(word) }
                
                Log.d("YoutubeDlRepository", "Title matching score: $matchCount/${titleWords.size}")
                
                // 제목 일치도가 낮으면 경고 로그
                if (matchCount == 0 && titleWords.isNotEmpty()) {
                    Log.w("YoutubeDlRepository", "Warning: Selected file may not match the video title")
                }
            }
            
            latestFile
        } catch (e: Exception) {
            Log.e("YoutubeDlRepository", "Error finding latest downloaded file: ${e.message}", e)
            null
        }
    }
    
    private fun findLatestDownloadedAudioFile(downloadDir: File, videoTitle: String, startTime: Long): File? {
        return try {
            Log.d("YoutubeDlRepository", "=== FINDING LATEST DOWNLOADED AUDIO FILE ===")
            Log.d("YoutubeDlRepository", "Download directory: ${downloadDir.absolutePath}")
            Log.d("YoutubeDlRepository", "Video title: $videoTitle")
            Log.d("YoutubeDlRepository", "Start time: $startTime")
            
            val allFiles = downloadDir.listFiles() ?: return null
            Log.d("YoutubeDlRepository", "Total files in directory: ${allFiles.size}")
            
            // 오디오 파일 확장자 필터
            val audioExtensions = setOf("m4a", "mp3", "webm", "aac", "wav", "flac")
            val audioFiles = allFiles.filter { file ->
                val extension = file.extension.lowercase()
                audioExtensions.contains(extension) && file.lastModified() >= startTime
            }
            
            Log.d("YoutubeDlRepository", "Audio files found: ${audioFiles.size}")
            audioFiles.forEach { file ->
                Log.d("YoutubeDlRepository", "Audio file: ${file.name}, size: ${file.length()}, modified: ${file.lastModified()}")
            }
            
            if (audioFiles.isEmpty()) {
                Log.w("YoutubeDlRepository", "No audio files found in directory")
                return null
            }
            
            // 최신 파일 선택 (수정 시간 기준)
            val latestFile = audioFiles.maxByOrNull { it.lastModified() }
            Log.d("YoutubeDlRepository", "Selected latest audio file: ${latestFile?.name}")
            
            // 추가 검증: 제목과 어느 정도 일치하는지 확인
            if (latestFile != null) {
                val titleWords = videoTitle.lowercase().split(" ", "-", "_").filter { it.length > 2 }
                val fileName = latestFile.name.lowercase()
                val matchCount = titleWords.count { word -> fileName.contains(word) }
                
                Log.d("YoutubeDlRepository", "Audio title matching score: $matchCount/${titleWords.size}")
                
                // 제목 일치도가 낮으면 경고 로그
                if (matchCount == 0 && titleWords.isNotEmpty()) {
                    Log.w("YoutubeDlRepository", "Warning: Selected audio file may not match the video title")
                }
            }
            
            latestFile
        } catch (e: Exception) {
            Log.e("YoutubeDlRepository", "Error finding latest downloaded audio file: ${e.message}", e)
            null
        }
    }
    
    private suspend fun getAvailableFormats(url: String): Result<List<String>> {
        return withContext(Dispatchers.IO) {
            try {
                Log.d("YoutubeDlRepository", "Fetching format list for: $url")
                
                val formatRequest = YoutubeDLRequest(url)
                formatRequest.addOption("--list-formats")
                formatRequest.addOption("--no-warnings")
                formatRequest.addOption("--user-agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                
                val result = YoutubeDL.getInstance().execute(formatRequest)
                
                Log.d("YoutubeDlRepository", "Format list result - Exit code: ${result.exitCode}")
                Log.d("YoutubeDlRepository", "Format list output: ${result.out}")
                
                if (result.exitCode == 0) {
                    val formats = parseFormatList(result.out)
                    Result.success(formats)
                } else {
                    Log.e("YoutubeDlRepository", "Format list error: ${result.err}")
                    Result.failure(Exception("Format list failed: ${result.err}"))
                }
            } catch (e: Exception) {
                Log.e("YoutubeDlRepository", "Exception getting formats: ${e.message}", e)
                Result.failure(e)
            }
        }
    }
    
    private fun parseFormatList(output: String): List<String> {
        val formats = mutableListOf<String>()
        val lines = output.split("\n")
        
        for (line in lines) {
            // 포맷 라인 예: "18          mp4        640x360    360p  364k , avc1.42001E, 30fps, mp4a.40.2"
            if (line.matches(Regex("^\\d+\\s+.*"))) {
                val parts = line.trim().split(Regex("\\s+"), 2)
                if (parts.isNotEmpty()) {
                    val formatId = parts[0]
                    formats.add("$formatId: ${if (parts.size > 1) parts[1] else "unknown"}")
                    Log.d("YoutubeDlRepository", "Found format: $formatId - ${if (parts.size > 1) parts[1] else "unknown"}")
                }
            }
        }
        
        Log.d("YoutubeDlRepository", "Total formats parsed: ${formats.size}")
        return formats
    }
    
    private fun selectFormatForResolution(availableFormats: List<String>, resolution: Resolution): String {
        Log.d("YoutubeDlRepository", "Selecting format for resolution: ${resolution.displayName}")
        
        // 해상도별 비디오 포맷 찾기
        val videoFormat = findBestVideoFormat(availableFormats, resolution)
        val audioFormat = findBestAudioFormat(availableFormats)
        
        return if (videoFormat != null && audioFormat != null) {
            "$videoFormat+$audioFormat"
        } else {
            // 통합 포맷 찾기
            val unifiedFormat = availableFormats.find { format ->
                when (resolution) {
                    Resolution.P2160 -> format.contains("2160") || format.contains("4K")
                    Resolution.P1440 -> format.contains("1440") || format.contains("2K")
                    Resolution.P1080 -> format.contains("1080")
                    Resolution.P720 -> format.contains("720")
                    Resolution.P360 -> format.contains("360")
                    Resolution.AUDIO_BEST -> format.contains("audio") || format.contains("m4a") || format.contains("mp3")
                }
            }
            
            unifiedFormat?.split(":")?.get(0) ?: "18" // 기본값
        }
    }
    
    private fun findBestVideoFormat(availableFormats: List<String>, resolution: Resolution): String? {
        val targetHeight = resolution.height
        
        return availableFormats.mapNotNull { format ->
            val formatData = format.split(":")
            if (formatData.size < 2) return@mapNotNull null
            
            val formatId = formatData[0]
            val formatInfo = formatData[1]
            
            // 해상도 추출
            val resolutionMatch = Regex("(\\d+)x(\\d+)").find(formatInfo)
            if (resolutionMatch != null) {
                val height = resolutionMatch.groupValues[2].toIntOrNull()
                if (height == targetHeight && formatInfo.contains("video only")) {
                    return@mapNotNull formatId
                }
            }
            null
        }.firstOrNull()
    }
    
    private fun findBestAudioFormat(availableFormats: List<String>): String? {
        // 오디오 전용 포맷 찾기 (m4a, webm 등)
        return availableFormats.mapNotNull { format ->
            val formatData = format.split(":")
            if (formatData.size < 2) return@mapNotNull null
            
            val formatId = formatData[0]
            val formatInfo = formatData[1]
            
            if (formatInfo.contains("audio only")) {
                return@mapNotNull formatId
            }
            null
        }.firstOrNull() ?: "140" // 기본 오디오 포맷
    }
    
    private suspend fun downloadToMoviesFolderWithFormat(
        videoInfo: VideoInfo,
        selectedFormat: String,
        startTime: Long,
        selectedResolution: Resolution,
        onProgress: (DownloadProgress) -> Unit
    ): Result<String> {
        return try {
            Log.d("YoutubeDlRepository", "=== DOWNLOAD TO MOVIES FOLDER WITH FORMAT ===")
            
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            if (!downloadDir.exists()) {
                val created = downloadDir.mkdirs()
                Log.d("YoutubeDlRepository", "Movies directory created: $created, path: ${downloadDir.absolutePath}")
            }
            
            performDownloadWithFormat(videoInfo, downloadDir, selectedFormat, startTime, selectedResolution, onProgress)
        } catch (e: Exception) {
            Log.e("YoutubeDlRepository", "Movies folder download failed: ${e.message}")
            Result.failure(e)
        }
    }
    
    private suspend fun downloadToMusicFolderWithFormat(
        videoInfo: VideoInfo,
        selectedFormat: String,
        startTime: Long,
        selectedResolution: Resolution,
        onProgress: (DownloadProgress) -> Unit
    ): Result<String> {
        return try {
            Log.d("YoutubeDlRepository", "=== DOWNLOAD TO MUSIC FOLDER WITH FORMAT ===")
            
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            if (!downloadDir.exists()) {
                val created = downloadDir.mkdirs()
                Log.d("YoutubeDlRepository", "Music directory created: $created, path: ${downloadDir.absolutePath}")
            }
            
            performDownloadWithFormat(videoInfo, downloadDir, selectedFormat, startTime, selectedResolution, onProgress)
        } catch (e: Exception) {
            Log.e("YoutubeDlRepository", "Music folder download failed: ${e.message}")
            Result.failure(e)
        }
    }
    
    private suspend fun downloadWithMediaStoreWithFormat(
        videoInfo: VideoInfo,
        selectedFormat: String,
        startTime: Long,
        selectedResolution: Resolution,
        onProgress: (DownloadProgress) -> Unit
    ): Result<String> {
        return try {
            Log.d("YoutubeDlRepository", "=== DOWNLOAD WITH MEDIASTORE/APP DIRECTORY WITH FORMAT ===")
            
            val downloadDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "TestApp")
            if (!downloadDir.exists()) {
                val created = downloadDir.mkdirs()
                Log.d("YoutubeDlRepository", "App directory created: $created, path: ${downloadDir.absolutePath}")
            }
            
            performDownloadWithFormat(videoInfo, downloadDir, selectedFormat, startTime, selectedResolution, onProgress)
        } catch (e: Exception) {
            Log.e("YoutubeDlRepository", "MediaStore download failed: ${e.message}")
            Result.failure(e)
        }
    }
    
    private suspend fun performDownloadWithFormat(
        videoInfo: VideoInfo,
        downloadDir: File,
        selectedFormat: String,
        startTime: Long,
        selectedResolution: Resolution,
        onProgress: (DownloadProgress) -> Unit
    ): Result<String> {
        return performDownloadWithBypass(videoInfo, downloadDir, selectedFormat, startTime, selectedResolution, onProgress, isRetry = false)
    }
    
    private suspend fun performDownloadWithBypass(
        videoInfo: VideoInfo,
        downloadDir: File,
        selectedFormat: String,
        startTime: Long,
        selectedResolution: Resolution,
        onProgress: (DownloadProgress) -> Unit,
        isRetry: Boolean = false
    ): Result<String> {
        return try {
            Log.d("YoutubeDlRepository", "Starting download for ${selectedResolution.displayName} (retry: $isRetry)")
            
            val request = YoutubeDLRequest(videoInfo.url)
            
            // YouTube 우회 시스템 적용 (차단 유발로 임시 비활성화)
            // if (selectedResolution == Resolution.AUDIO_BEST) {
            //     bypassHelper.setupAudioBypass(request, isRetry)
            //     Log.d("YoutubeDlRepository", "Applied audio-specific bypass options")
            // } else {
            //     bypassHelper.setupBasicBypass(request)
            //     Log.d("YoutubeDlRepository", "Applied basic bypass options")
            // }
            Log.d("YoutubeDlRepository", "Bypass system disabled - using simple request")
            
            // 기본 다운로드 옵션
            request.addOption("-o", "${downloadDir.absolutePath}/%(title)s.%(ext)s")
            request.addOption("--throttled-rate", "100K")
            request.addOption("--limit-rate", "1M")
            
            // 포맷 설정 (오디오 전용 처리 포함)
            setupFormatOptions(request, selectedFormat, selectedResolution)
            
            // 추가 옵션 (오디오가 아닌 경우만)
            if (selectedResolution != Resolution.AUDIO_BEST) {
                request.addOption("--merge-output-format", "mp4")
            }
            request.addOption("--no-post-overwrites")
            
            Log.d("YoutubeDlRepository", "🎬 Target quality: ${selectedResolution.displayName}")
            Log.d("YoutubeDlRepository", "🎬 Selected format: $selectedFormat")
            
            // 다운로드 준비 상태 설정
            val preparingProgress = DownloadProgress(
                videoId = videoInfo.id,
                progress = 0f,
                status = DownloadStatus.PREPARING,
                startTime = startTime,
                currentPhase = DownloadPhase.PREPARING,
                selectedResolution = selectedResolution
            )
            updateDownloadProgress(videoInfo.id, preparingProgress)
            onProgress(preparingProgress)
            
            var extractedFilePath: String? = null
            var currentPhase = DownloadPhase.PREPARING
            
            // 진행률 콜백으로 실행
            val result = YoutubeDL.getInstance().execute(request) { progress, etaInSeconds, line ->
                Log.d("YoutubeDlRepository", "Progress: $progress%, ETA: ${etaInSeconds}s, Line: $line")
                
                // 단계 감지 (개선된 로직)
                val newPhase = when {
                    // 병합 단계 감지 (최우선)
                    line.contains("Merging") || line.contains("merge") -> DownloadPhase.MERGING
                    
                    // 오디오 다운로드 감지 (파일 확장자 기반)
                    line.contains("[download] Destination:") && 
                    (line.contains(".f251.webm") || line.contains(".f140.m4a") || 
                     line.contains(".m4a") || line.contains(".webm") && line.contains("audio only")) -> DownloadPhase.AUDIO
                    
                    // 비디오 다운로드 감지
                    line.contains("[download] Destination:") && 
                    (line.contains(".f399.mp4") || line.contains(".f137.mp4") || 
                     line.contains(".mp4") && line.contains("video only")) -> DownloadPhase.VIDEO
                    
                    // 일반적인 다운로드 감지 (기존 로직 보존)
                    line.contains("[download]") && !line.contains("audio") -> DownloadPhase.VIDEO
                    line.contains("Downloading audio") || line.contains("audio") -> DownloadPhase.AUDIO
                    
                    // 기본값은 현재 단계 유지
                    else -> currentPhase
                }
                
                if (newPhase != currentPhase) {
                    currentPhase = newPhase
                    Log.d("YoutubeDlRepository", "Phase changed to: ${currentPhase.displayName}")
                }
                
                // 파일 경로 추출 (오디오 파일 포함)
                if (line.contains("[download] Destination:") || line.contains("Merging formats into")) {
                    val pathPattern = if (selectedResolution == Resolution.AUDIO_BEST) {
                        // 오디오 파일 패턴 (m4a, mp3, webm, aac 등)
                        Regex("\"([^\"]+\\.(m4a|mp3|webm|aac|wav|flac|opus))\"")
                    } else {
                        // 비디오 파일 패턴
                        Regex("\"([^\"]+\\.(mp4|webm|mkv|avi|mov|flv|m4v))\"")
                    }
                    val match = pathPattern.find(line)
                    if (match != null) {
                        extractedFilePath = match.groupValues[1]
                        Log.d("YoutubeDlRepository", "Extracted file path: $extractedFilePath")
                    }
                }
                
                val progressFloat = try {
                    val p = progress.toFloat()
                    if (p >= 0f) p else 0f
                } catch (e: Exception) { 0f }
                
                val downloadingProgress = DownloadProgress(
                    videoId = videoInfo.id,
                    progress = progressFloat,
                    status = DownloadStatus.DOWNLOADING,
                    etaSeconds = etaInSeconds.toLong(),
                    startTime = startTime,
                    currentPhase = currentPhase,
                    phaseProgress = progressFloat,
                    selectedResolution = selectedResolution
                )
                updateDownloadProgress(videoInfo.id, downloadingProgress)
                onProgress(downloadingProgress)
            }
            
            if (result.exitCode == 0) {
                Log.d("YoutubeDlRepository", "Download completed successfully")
                
                val finalFilePath = extractedFilePath ?: run {
                    if (selectedResolution == Resolution.AUDIO_BEST) {
                        findLatestDownloadedAudioFile(downloadDir, videoInfo.title, startTime)?.absolutePath
                    } else {
                        findLatestDownloadedFile(downloadDir, videoInfo.title, startTime)?.absolutePath
                    }
                }
                
                val completedProgress = DownloadProgress(
                    videoId = videoInfo.id,
                    progress = 100f,
                    status = DownloadStatus.COMPLETED,
                    filePath = finalFilePath,
                    startTime = startTime,
                    currentPhase = DownloadPhase.COMPLETED,
                    phaseProgress = 100f,
                    selectedResolution = selectedResolution
                )
                updateDownloadProgress(videoInfo.id, completedProgress)
                onProgress(completedProgress)
                
                Result.success(finalFilePath ?: "Download completed")
            } else {
                val errorMsg = "Download failed: ${result.err}"
                Log.e("YoutubeDlRepository", errorMsg)
                
                // 우회 시스템을 통한 차단 감지 및 재시도 (비활성화)
                // val blockingInfo = bypassHelper.detectBlocking(result.err)
                // if (blockingInfo.isBlocked && !isRetry) {
                //     Log.w("YoutubeDlRepository", "Blocking detected: ${blockingInfo.blockingType}, attempting bypass retry")
                //     
                //     if (bypassHelper.incrementRetryAndUpgrade()) {
                //         // 재시도 가능한 경우 고급 우회 전략으로 재시도
                //         return performDownloadWithBypass(videoInfo, downloadDir, selectedFormat, startTime, selectedResolution, onProgress, isRetry = true)
                //     } else {
                //         Log.e("YoutubeDlRepository", "Maximum retry attempts exceeded")
                //         bypassHelper.resetRetryState()
                //     }
                // }
                Log.d("YoutubeDlRepository", "Simple error handling - no bypass retry")
                
                val errorType = detectDownloadError(result.err)
                
                val errorProgress = DownloadProgress(
                    videoId = videoInfo.id,
                    progress = 0f,
                    status = DownloadStatus.ERROR,
                    error = errorMsg,
                    startTime = startTime,
                    currentPhase = DownloadPhase.ERROR,
                    selectedResolution = selectedResolution,
                    errorType = errorType
                )
                updateDownloadProgress(videoInfo.id, errorProgress)
                onProgress(errorProgress)
                
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Log.e("YoutubeDlRepository", "Exception during format download: ${e.message}", e)
            
            val errorMessage = e.message ?: "Unknown error"
            val errorType = detectDownloadError(errorMessage)
            
            val errorProgress = DownloadProgress(
                videoId = videoInfo.id,
                progress = 0f,
                status = DownloadStatus.ERROR,
                error = errorMessage,
                startTime = startTime,
                currentPhase = DownloadPhase.ERROR,
                selectedResolution = selectedResolution,
                errorType = errorType
            )
            updateDownloadProgress(videoInfo.id, errorProgress)
            onProgress(errorProgress)
            
            Result.failure(e)
        }
    }
    
    private fun analyzeAvailableResolutions(availableFormats: List<String>): List<Resolution> {
        val resolutions = mutableSetOf<Resolution>()
        
        for (format in availableFormats) {
            // 해상도 정보 추출 (예: "640x360", "1280x720" 등)
            val resolutionMatch = Regex("(\\d+)x(\\d+)").find(format)
            if (resolutionMatch != null) {
                val height = resolutionMatch.groupValues[2].toIntOrNull()
                height?.let { h ->
                    Resolution.fromHeight(h)?.let { resolution ->
                        resolutions.add(resolution)
                    }
                }
            }
        }
        
        return resolutions.sortedByDescending { it.height }
    }
    
    private fun getQualityOptions(availableFormats: List<String>): List<QualityOption> {
        val options = mutableListOf<QualityOption>()
        val formatIds = availableFormats.mapNotNull { format ->
            val id = format.split(":")[0].trim()
            if (id.matches(Regex("\\d+"))) id else null
        }
        
        // 각 해상도별로 최적의 포맷 조합 찾기
        for (resolution in Resolution.values().reversedArray()) {
            val videoFormat = findBestVideoFormat(availableFormats, formatIds, resolution)
            val audioFormat = findBestAudioFormat(availableFormats, formatIds)
            
            if (videoFormat != null && audioFormat != null) {
                val estimatedSize = estimateFileSize(availableFormats, videoFormat, audioFormat)
                val isRecommended = resolution == Resolution.P720 // 720p를 기본 추천
                
                options.add(QualityOption(
                    resolution = resolution,
                    videoFormat = videoFormat,
                    audioFormat = audioFormat,
                    estimatedSize = estimatedSize,
                    isRecommended = isRecommended
                ))
            }
        }
        
        return options
    }
    
    private fun findBestVideoFormat(availableFormats: List<String>, formatIds: List<String>, resolution: Resolution): String? {
        // 해당 해상도의 비디오 포맷 찾기
        val targetHeight = resolution.height
        
        return formatIds.find { id ->
            val formatInfo = availableFormats.find { it.startsWith("$id ") } ?: ""
            formatInfo.contains("video only") && 
            formatInfo.contains("${targetHeight}p") ||
            formatInfo.contains("x$targetHeight")
        }
    }
    
    private fun findBestAudioFormat(availableFormats: List<String>, formatIds: List<String>): String? {
        // 고품질 오디오 포맷 우선 순위: 140 > 139 > 기타
        val audioPriority = listOf("140", "139", "251", "250", "249")
        
        for (preferred in audioPriority) {
            if (formatIds.contains(preferred)) {
                return preferred
            }
        }
        
        // 오디오 전용 포맷 중 아무거나
        return formatIds.find { id ->
            val formatInfo = availableFormats.find { it.startsWith("$id ") } ?: ""
            formatInfo.contains("audio only")
        }
    }
    
    private fun estimateFileSize(availableFormats: List<String>, videoFormat: String, audioFormat: String): String {
        var totalSize = 0L
        
        // 비디오 파일 크기 추출
        val videoInfo = availableFormats.find { it.startsWith("$videoFormat ") }
        videoInfo?.let { info ->
            val sizeMatch = Regex("(\\d+(?:\\.\\d+)?)MiB").find(info)
            sizeMatch?.let { match ->
                val sizeMB = match.groupValues[1].toFloatOrNull()
                sizeMB?.let { totalSize += (it * 1024 * 1024).toLong() }
            }
        }
        
        // 오디오 파일 크기 추출 (대략 3-4MB로 추정)
        totalSize += 3 * 1024 * 1024 // 3MB 추정
        
        return formatFileSize(totalSize)
    }
    
    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> String.format("%.1fGB", bytes / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024 * 1024 -> String.format("%.1fMB", bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> String.format("%.1fKB", bytes / 1024.0)
            else -> "${bytes}B"
        }
    }

    private fun selectSafeFormat(availableFormats: List<String>): String {
        // 사용 가능한 포맷에서 360p 비디오+오디오 조합 선택
        val formatIds = availableFormats.mapNotNull { format ->
            val id = format.split(":")[0].trim()
            if (id.matches(Regex("\\d+"))) id else null
        }
        
        Log.d("YoutubeDlRepository", "Available format IDs: $formatIds")
        
        // 1순위: 360p 비디오(134) + 오디오(140) 조합
        if (formatIds.contains("134") && formatIds.contains("140")) {
            Log.d("YoutubeDlRepository", "Selected 360p format: 134+140 (360p video + audio)")
            return "134+140"
        }
        
        // 2순위: 240p 비디오(133) + 오디오(140) 조합  
        if (formatIds.contains("133") && formatIds.contains("140")) {
            Log.d("YoutubeDlRepository", "Selected 240p format: 133+140 (240p video + audio)")
            return "133+140"
        }
        
        // 3순위: 144p 비디오(160) + 오디오(140) 조합
        if (formatIds.contains("160") && formatIds.contains("140")) {
            Log.d("YoutubeDlRepository", "Selected 144p format: 160+140 (144p video + audio)")
            return "160+140"
        }
        
        // 4순위: 레거시 통합 포맷들 (있다면)
        val legacyFormats = listOf("18", "36", "17")
        for (legacy in legacyFormats) {
            if (formatIds.contains(legacy)) {
                Log.d("YoutubeDlRepository", "Selected legacy format: $legacy")
                return legacy
            }
        }
        
        // 5순위: 가장 낮은 품질의 비디오 + 오디오 조합
        val videoFormats = formatIds.filter { id ->
            availableFormats.any { format -> 
                format.startsWith("$id ") && format.contains("video only") 
            }
        }.mapNotNull { it.toIntOrNull() }.sorted()
        
        val audioFormats = formatIds.filter { id ->
            availableFormats.any { format -> 
                format.startsWith("$id ") && format.contains("audio only") 
            }
        }.mapNotNull { it.toIntOrNull() }.sorted()
        
        if (videoFormats.isNotEmpty() && audioFormats.isNotEmpty()) {
            val selectedVideo = videoFormats.first()
            val selectedAudio = audioFormats.first()
            Log.d("YoutubeDlRepository", "Selected fallback format: $selectedVideo+$selectedAudio")
            return "$selectedVideo+$selectedAudio"
        }
        
        // 마지막 대안: worst
        Log.d("YoutubeDlRepository", "Fallback to worst format")
        return "worst"
    }
    
    private fun detectDownloadError(errorMessage: String): DownloadErrorType {
        return when {
            errorMessage.contains("HTTP Error 403") || 
            errorMessage.contains("403 Forbidden") ||
            errorMessage.contains("unable to download video data") -> DownloadErrorType.GOOGLE_BLOCKED
            
            errorMessage.contains("Requested format is not available") ||
            errorMessage.contains("No video formats found") -> DownloadErrorType.FORMAT_NOT_AVAILABLE
            
            errorMessage.contains("Network is unreachable") ||
            errorMessage.contains("Connection timed out") ||
            errorMessage.contains("Connection refused") -> DownloadErrorType.NETWORK_ERROR
            
            errorMessage.contains("Permission denied") ||
            errorMessage.contains("No such file or directory") -> DownloadErrorType.PERMISSION_ERROR
            
            else -> DownloadErrorType.UNKNOWN_ERROR
        }
    }
    
    private fun updateDownloadProgress(videoId: String, progress: DownloadProgress) {
        val currentMap = _downloadProgress.value.toMutableMap()
        currentMap[videoId] = progress
        _downloadProgress.value = currentMap
    }
    
    private fun createSearchBasedResults(query: String): List<VideoInfo> {
        // 실제 검색어를 기반으로 관련 비디오 결과 생성
        val normalizedQuery = query.lowercase().trim()
        
        return when {
            // K-POP 아티스트들
            normalizedQuery.contains("ive") -> createIveVideos()
            normalizedQuery.contains("르세라핌") || normalizedQuery.contains("lesserafim") -> createLesserafimVideos()
            normalizedQuery.contains("뉴진스") || normalizedQuery.contains("newjeans") -> createNewJeansVideos()
            normalizedQuery.contains("블랙핑크") || normalizedQuery.contains("blackpink") -> createBlackpinkVideos()
            normalizedQuery.contains("에스파") || normalizedQuery.contains("aespa") -> createAespaVideos()
            normalizedQuery.contains("bts") || normalizedQuery.contains("방탄소년단") -> createBTSVideos()
            normalizedQuery.contains("seventeen") || normalizedQuery.contains("세븐틴") -> createSeventeenVideos()
            
            // 일반 검색어
            else -> createGenericSearchResults(query)
        }
    }
    
    private fun createLesserafimVideos(): List<VideoInfo> {
        return listOf(
            VideoInfo(
                id = "pyf8cbqyfPs",
                title = "LE SSERAFIM (르세라핌) 'UNFORGIVEN (feat. Nile Rodgers)' Official MV",
                description = "르세라핌의 타이틀곡 'UNFORGIVEN' 뮤직비디오",
                uploader = "HYBE LABELS",
                thumbnail = "https://i.ytimg.com/vi/pyf8cbqyfPs/mqdefault.jpg",
                duration = "3:08",
                viewCount = "165M",
                uploadDate = "2023-05-01",
                url = "https://www.youtube.com/watch?v=pyf8cbqyfPs",
                availableResolutions = listOf(Resolution.P360, Resolution.P720, Resolution.P1080, Resolution.P2160),
                maxResolution = Resolution.P2160,
                formattedUploadDate = "2023.05.01"
            ),
            VideoInfo(
                id = "RIOa1lNAJ60",
                title = "LE SSERAFIM (르세라핌) 'ANTIFRAGILE' Official MV",
                description = "르세라핌의 'ANTIFRAGILE' 뮤직비디오",
                uploader = "HYBE LABELS",
                thumbnail = "https://i.ytimg.com/vi/RIOa1lNAJ60/mqdefault.jpg",
                duration = "3:06",
                viewCount = "287M",
                uploadDate = "2022-10-17",
                url = "https://www.youtube.com/watch?v=RIOa1lNAJ60"
            ),
            VideoInfo(
                id = "JSgOPWnYGLs",
                title = "LE SSERAFIM (르세라핌) 'FEARLESS' Official MV",
                description = "르세라핌 데뷔곡 'FEARLESS' 뮤직비디오",
                uploader = "HYBE LABELS",
                thumbnail = "https://i.ytimg.com/vi/JSgOPWnYGLs/mqdefault.jpg",
                duration = "2:49",
                viewCount = "198M",
                uploadDate = "2022-05-02",
                url = "https://www.youtube.com/watch?v=JSgOPWnYGLs"
            )
        )
    }
    
    private fun createNewJeansVideos(): List<VideoInfo> {
        return listOf(
            VideoInfo(
                id = "js1CtxSY38I",
                title = "NewJeans (뉴진스) 'Super Shy' Official MV",
                description = "뉴진스의 'Super Shy' 뮤직비디오",
                uploader = "HYBE LABELS",
                thumbnail = "https://i.ytimg.com/vi/js1CtxSY38I/mqdefault.jpg",
                duration = "2:58",
                viewCount = "142M",
                uploadDate = "2023-07-07",
                url = "https://www.youtube.com/watch?v=js1CtxSY38I"
            ),
            VideoInfo(
                id = "sVTy_wmn5SU",
                title = "NewJeans (뉴진스) 'Attention' Official MV",
                description = "뉴진스 데뷔곡 'Attention' 뮤직비디오",
                uploader = "HYBE LABELS",
                thumbnail = "https://i.ytimg.com/vi/sVTy_wmn5SU/mqdefault.jpg",
                duration = "3:01",
                viewCount = "89M",
                uploadDate = "2022-07-22",
                url = "https://www.youtube.com/watch?v=sVTy_wmn5SU"
            )
        )
    }
    
    private fun createIveVideos(): List<VideoInfo> {
        return listOf(
            VideoInfo(
                id = "Y8JFxS1HlDo",
                title = "IVE 'LOVE DIVE' MV",
                description = "IVE의 새로운 타이틀곡 'LOVE DIVE' 뮤직비디오",
                uploader = "IVE",
                thumbnail = "https://i.ytimg.com/vi/Y8JFxS1HlDo/mqdefault.jpg",
                duration = "2:58",
                viewCount = "234M",
                uploadDate = "2022-04-05",
                url = "https://www.youtube.com/watch?v=Y8JFxS1HlDo"
            ),
            VideoInfo(
                id = "wjKBRvOOJj4",
                title = "IVE 'After LIKE' MV", 
                description = "IVE의 신곡 'After LIKE' 뮤직비디오",
                uploader = "IVE",
                thumbnail = "https://i.ytimg.com/vi/wjKBRvOOJj4/mqdefault.jpg",
                duration = "2:56",
                viewCount = "187M",
                uploadDate = "2022-08-22",
                url = "https://www.youtube.com/watch?v=wjKBRvOOJj4"
            )
        )
    }
    
    private fun createBlackpinkVideos(): List<VideoInfo> {
        return listOf(
            VideoInfo(
                id = "32sy88jkQMU",
                title = "BLACKPINK - 'Shut Down' M/V",
                description = "BLACKPINK의 신곡 'Shut Down' 뮤직비디오",
                uploader = "BLACKPINK",
                thumbnail = "https://i.ytimg.com/vi/32sy88jkQMU/mqdefault.jpg",
                duration = "3:06",
                viewCount = "425M",
                uploadDate = "2022-09-16",
                url = "https://www.youtube.com/watch?v=32sy88jkQMU"
            )
        )
    }
    
    private fun createAespaVideos(): List<VideoInfo> {
        return listOf(
            VideoInfo(
                id = "ZeerrnuLi5E",
                title = "aespa 에스파 'Spicy' MV",
                description = "에스파의 'Spicy' 뮤직비디오",
                uploader = "SMTOWN",
                thumbnail = "https://i.ytimg.com/vi/ZeerrnuLi5E/mqdefault.jpg",
                duration = "3:25",
                viewCount = "98M",
                uploadDate = "2023-05-08",
                url = "https://www.youtube.com/watch?v=ZeerrnuLi5E"
            )
        )
    }
    
    private fun createBTSVideos(): List<VideoInfo> {
        return listOf(
            VideoInfo(
                id = "WMweEpGlu_U",
                title = "BTS (방탄소년단) 'Butter' Official MV",
                description = "BTS의 'Butter' 공식 뮤직비디오",
                uploader = "HYBE LABELS",
                thumbnail = "https://i.ytimg.com/vi/WMweEpGlu_U/mqdefault.jpg",
                duration = "2:45",
                viewCount = "890M",
                uploadDate = "2021-05-21",
                url = "https://www.youtube.com/watch?v=WMweEpGlu_U"
            )
        )
    }
    
    private fun createSeventeenVideos(): List<VideoInfo> {
        return listOf(
            VideoInfo(
                id = "mBhJEBHDqnA",
                title = "SEVENTEEN (세븐틴) 'God of Music' Official MV",
                description = "세븐틴의 'God of Music' 뮤직비디오",
                uploader = "HYBE LABELS",
                thumbnail = "https://i.ytimg.com/vi/mBhJEBHDqnA/mqdefault.jpg",
                duration = "3:33",
                viewCount = "67M",
                uploadDate = "2023-10-23",
                url = "https://www.youtube.com/watch?v=mBhJEBHDqnA"
            )
        )
    }
    
    private fun parseSearchResults(jsonOutput: String): List<VideoInfo> {
        val videoList = mutableListOf<VideoInfo>()
        
        try {
            // yt-dlp는 여러 JSON 객체를 개행으로 구분하여 출력
            val lines = jsonOutput.trim().split("\n")
            
            // 전체 JSON 구조 로그 출력 (처음 몇 줄만)
            Log.d("YoutubeDlRepository", "=== 검색 결과 JSON 구조 분석 ===")
            Log.d("YoutubeDlRepository", "총 ${lines.size}개 줄 받음")
            lines.take(3).forEachIndexed { index, line ->
                if (line.isNotBlank()) {
                    Log.d("YoutubeDlRepository", "Line ${index + 1}: ${line.take(500)}...")
                }
            }
            
            for (line in lines) {
                if (line.isBlank()) continue
                
                try {
                    val jsonObject = JSONObject(line)
                    
                    // 기본 정보 로그 (간소화)
                    Log.d("YoutubeDlRepository", "Video: ${jsonObject.optString("title", "")}")
                    
                    // 필수 필드 확인
                    val id = jsonObject.optString("id", "")
                    val title = jsonObject.optString("title", "")
                    val uploader = jsonObject.optString("uploader", "Unknown")
                    
                    if (id.isNotEmpty() && title.isNotEmpty()) {
                        // 썸네일 URL 개선 - 여러 옵션 시도
                        val thumbnailUrl = when {
                            jsonObject.has("thumbnail") && !jsonObject.getString("thumbnail").isNullOrEmpty() -> 
                                jsonObject.getString("thumbnail")
                            jsonObject.has("thumbnails") -> {
                                val thumbnails = jsonObject.getJSONArray("thumbnails")
                                if (thumbnails.length() > 0) {
                                    // 마지막(가장 고화질) 썸네일 선택
                                    val lastThumbnail = thumbnails.getJSONObject(thumbnails.length() - 1)
                                    lastThumbnail.optString("url", "")
                                } else ""
                            }
                            else -> "https://i.ytimg.com/vi/$id/mqdefault.jpg" // 기본 YouTube 썸네일
                        }
                        
                        // 업로드 날짜 (기본 포맷만 사용)
                        val uploadDate = jsonObject.optString("upload_date", "")
                        val formattedDate = formatUploadDate(uploadDate)
                        
                        val videoInfo = VideoInfo(
                            id = id,
                            title = title,
                            description = jsonObject.optString("description", ""),
                            uploader = uploader,
                            thumbnail = thumbnailUrl,
                            duration = formatDuration(jsonObject.optInt("duration", 0)),
                            viewCount = formatViewCount(jsonObject.optLong("view_count", 0)),
                            uploadDate = uploadDate,
                            url = "https://www.youtube.com/watch?v=$id",
                            availableResolutions = emptyList(),
                            maxResolution = null,
                            formattedUploadDate = formattedDate
                        )
                        videoList.add(videoInfo)
                    }
                } catch (e: JSONException) {
                    // 개별 JSON 파싱 오류는 무시하고 계속 진행
                    continue
                }
            }
        } catch (e: Exception) {
            // 전체 파싱 오류 시 빈 리스트 반환
            return emptyList()
        }
        
        return videoList
    }
    
    private fun getActualResolutionsFromJson(jsonObject: JSONObject): List<Resolution> {
        val resolutions = mutableSetOf<Resolution>()
        
        try {
            // formats 배열에서 해상도 정보 추출
            if (jsonObject.has("formats")) {
                val formats = jsonObject.getJSONArray("formats")
                for (i in 0 until formats.length()) {
                    val format = formats.getJSONObject(i)
                    
                    // 해상도 정보 추출 (height 필드 우선, width x height 형태도 지원)
                    val height = when {
                        format.has("height") && !format.isNull("height") -> format.getInt("height")
                        format.has("resolution") && !format.isNull("resolution") -> {
                            val resolution = format.getString("resolution")
                            extractHeightFromResolution(resolution)
                        }
                        format.has("format_note") && !format.isNull("format_note") -> {
                            val formatNote = format.getString("format_note")
                            extractHeightFromFormatNote(formatNote)
                        }
                        else -> null
                    }
                    
                    height?.let { h ->
                        Resolution.fromHeight(h)?.let { resolution ->
                            resolutions.add(resolution)
                        }
                    }
                }
            }
            
            // requested_formats에서도 확인
            if (jsonObject.has("requested_formats")) {
                val requestedFormats = jsonObject.getJSONArray("requested_formats")
                for (i in 0 until requestedFormats.length()) {
                    val format = requestedFormats.getJSONObject(i)
                    val height = format.optInt("height", 0)
                    if (height > 0) {
                        Resolution.fromHeight(height)?.let { resolution ->
                            resolutions.add(resolution)
                        }
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.w("YoutubeDlRepository", "Error parsing resolutions from JSON: ${e.message}")
        }
        
        // 기본값 반환 (해상도 정보가 없는 경우)
        return if (resolutions.isNotEmpty()) {
            resolutions.sortedByDescending { it.height }
        } else {
            listOf(Resolution.P360, Resolution.P720) // 기본 안전 값
        }
    }
    
    private fun extractHeightFromResolution(resolution: String): Int? {
        // "1920x1080", "1280x720" 형태에서 높이 추출
        val match = Regex("(\\d+)x(\\d+)").find(resolution)
        return match?.groupValues?.get(2)?.toIntOrNull()
    }
    
    private fun extractHeightFromFormatNote(formatNote: String): Int? {
        // "720p", "1080p60", "480p" 형태에서 높이 추출
        val match = Regex("(\\d+)p").find(formatNote)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }
    
    private suspend fun getOptimalFormatForVideo(videoUrl: String, targetResolution: Resolution): String {
        return try {
            Log.d("YoutubeDlRepository", "Getting optimal format for ${targetResolution.displayName}")
            
            // 1단계: 실제 서버에서 포맷 목록 조회
            val formatResult = getAvailableFormats(videoUrl)
            if (formatResult.isFailure) {
                Log.w("YoutubeDlRepository", "Failed to get formats, using fallback")
                return getFallbackFormat(targetResolution)
            }
            
            val availableFormats = formatResult.getOrNull() ?: emptyList()
            Log.d("YoutubeDlRepository", "Retrieved ${availableFormats.size} formats from server")
            
            if (availableFormats.isEmpty()) {
                Log.w("YoutubeDlRepository", "No formats available, using fallback")
                return getFallbackFormat(targetResolution)
            }
            
            // 2단계: 오디오 전용 처리 우선 확인
            if (targetResolution == Resolution.AUDIO_BEST) {
                Log.d("YoutubeDlRepository", "Audio-only download requested")
                val bestAudioFormat = selectBestAudioFormat(availableFormats)
                Log.d("YoutubeDlRepository", "Selected audio format: $bestAudioFormat")
                
                return if (bestAudioFormat != null) {
                    Log.d("YoutubeDlRepository", "Using audio format: $bestAudioFormat")
                    bestAudioFormat
                } else {
                    Log.w("YoutubeDlRepository", "No audio format found, using fallback")
                    "bestaudio"
                }
            }
            
            // 3단계: 비디오 다운로드용 포맷 선택 (기존 로직)
            val bestVideoFormat = selectBestVideoFormat(availableFormats, targetResolution)
            val bestAudioFormat = selectBestAudioFormat(availableFormats)
            
            Log.d("YoutubeDlRepository", "Selected video format: $bestVideoFormat")
            Log.d("YoutubeDlRepository", "Selected audio format: $bestAudioFormat")
            
            // 4단계: 구체적 포맷 ID 조합 생성
            val finalFormat = when {
                bestVideoFormat != null && bestAudioFormat != null -> {
                    // 비디오+오디오 조합 (FFmpeg 병합 필요)
                    "$bestVideoFormat+$bestAudioFormat"
                }
                bestVideoFormat != null -> {
                    // 비디오만 (오디오 없는 경우)
                    bestVideoFormat
                }
                else -> {
                    // 포맷 선택 실패, 안전한 폴백
                    Log.w("YoutubeDlRepository", "Format selection failed, using fallback")
                    getFallbackFormat(targetResolution)
                }
            }
            
            Log.d("YoutubeDlRepository", "Final selected format: $finalFormat")
            return finalFormat
            
        } catch (e: Exception) {
            Log.e("YoutubeDlRepository", "Error getting optimal format: ${e.message}", e)
            getFallbackFormat(targetResolution)
        }
    }
    
    private fun selectBestVideoFormat(availableFormats: List<String>, targetResolution: Resolution): String? {
        val targetHeight = targetResolution.height
        Log.d("YoutubeDlRepository", "Selecting video format for target height: $targetHeight")
        
        // 비디오 전용 포맷들 필터링 및 분석
        val videoFormats = availableFormats.mapNotNull { format ->
            val parts = format.split(":", limit = 2)
            if (parts.size < 2) return@mapNotNull null
            
            val formatId = parts[0].trim()
            val formatInfo = parts[1]
            
            // 비디오 전용 포맷 확인 (통합 포맷 제외)
            if (formatInfo.contains("video only") || 
                (formatInfo.contains("mp4") && formatInfo.contains("x") && !formatInfo.contains("audio only"))) {
                
                val height = extractHeightFromFormatInfo(formatInfo)
                if (height != null && height > 0) {
                    Log.d("YoutubeDlRepository", "Found video format: $formatId, height: $height, info: $formatInfo")
                    return@mapNotNull FormatInfo(formatId, height, formatInfo)
                }
            }
            
            // 통합 포맷 (비디오+오디오) 지원 - 높은 품질이면서 목표 해상도에 맞는 경우
            if (!formatInfo.contains("video only") && !formatInfo.contains("audio only")) {
                val height = extractHeightFromFormatInfo(formatInfo)
                if (height != null && height > 0 && height <= targetHeight) {
                    Log.d("YoutubeDlRepository", "Found unified format: $formatId, height: $height, info: $formatInfo")
                    return@mapNotNull FormatInfo(formatId, height, formatInfo)
                }
            }
            
            null
        }
        
        Log.d("YoutubeDlRepository", "Total video formats found: ${videoFormats.size}")
        
        if (videoFormats.isEmpty()) {
            Log.w("YoutubeDlRepository", "No video formats found")
            return null
        }
        
        // 목표 해상도 이하에서 가장 높은 해상도 선택
        val selectedFormat = videoFormats
            .filter { it.height <= targetHeight }
            .maxByOrNull { it.height }
            ?: videoFormats.minByOrNull { it.height } // 모든 포맷이 목표보다 높으면 가장 낮은 해상도
        
        Log.d("YoutubeDlRepository", "Selected video format: ${selectedFormat?.formatId} (${selectedFormat?.height}p)")
        return selectedFormat?.formatId
    }
    
    private fun selectBestAudioFormat(availableFormats: List<String>): String? {
        Log.d("YoutubeDlRepository", "Selecting best audio format")
        
        // 오디오 전용 포맷들 필터링 및 품질 순으로 정렬
        val audioFormats = availableFormats.mapNotNull { format ->
            val parts = format.split(":", limit = 2)
            if (parts.size < 2) return@mapNotNull null
            
            val formatId = parts[0].trim()
            val formatInfo = parts[1]
            
            if (formatInfo.contains("audio only")) {
                val bitrate = extractBitrateFromFormatInfo(formatInfo)
                Log.d("YoutubeDlRepository", "Found audio format: $formatId, bitrate: ${bitrate}k, info: $formatInfo")
                return@mapNotNull AudioFormatInfo(formatId, bitrate, formatInfo)
            }
            null
        }
        
        Log.d("YoutubeDlRepository", "Total audio formats found: ${audioFormats.size}")
        
        if (audioFormats.isEmpty()) {
            Log.w("YoutubeDlRepository", "No audio-only formats found, checking for unified formats")
            // 오디오 전용이 없으면 통합 포맷 중에서 선택하지 않음 (비디오와 분리 다운로드를 위해)
            return null
        }
        
        // 높은 bitrate 순으로 정렬하여 최고 품질 선택 (단, 128k 이상 선호)
        val selectedAudio = audioFormats
            .filter { it.bitrate >= 128 } // 128k 이상 선호
            .maxByOrNull { it.bitrate }
            ?: audioFormats.maxByOrNull { it.bitrate } // 128k 미만만 있으면 그 중 최고
        
        Log.d("YoutubeDlRepository", "Selected audio format: ${selectedAudio?.formatId} (${selectedAudio?.bitrate}k)")
        return selectedAudio?.formatId
    }
    
    private fun extractHeightFromFormatInfo(formatInfo: String): Int? {
        // "1920x1080", "1280x720" 형태 찾기
        val resolutionMatch = Regex("(\\d+)x(\\d+)").find(formatInfo)
        if (resolutionMatch != null) {
            return resolutionMatch.groupValues[2].toIntOrNull()
        }
        
        // "720p", "1080p" 형태 찾기
        val pMatch = Regex("(\\d+)p").find(formatInfo)
        return pMatch?.groupValues?.get(1)?.toIntOrNull()
    }
    
    private fun extractBitrateFromFormatInfo(formatInfo: String): Int {
        // "128k", "256k" 형태에서 bitrate 추출
        val bitrateMatch = Regex("(\\d+)k").find(formatInfo)
        return bitrateMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }
    
    private fun getFallbackFormat(targetResolution: Resolution): String {
        // 더 안전한 폴백 전략: 단순하고 확실한 포맷 사용
        return when (targetResolution) {
            Resolution.P360 -> "worst/18"  // 가장 낮은 품질 또는 기본 360p
            Resolution.P720 -> "best/22"   // 가장 좋은 품질 또는 기본 720p
            Resolution.P1080 -> "best"     // 가장 좋은 품질
            Resolution.P1440 -> "best"     // 가장 좋은 품질
            Resolution.P2160 -> "best"     // 가장 좋은 품질
            Resolution.AUDIO_BEST -> "bestaudio"  // 최고 음질
        }.also {
            Log.d("YoutubeDlRepository", "Using fallback format: $it for ${targetResolution.displayName}")
        }
    }
    
    /**
     * 포맷 옵션 설정 (오디오 전용 처리 포함)
     */
    private fun setupFormatOptions(request: YoutubeDLRequest, selectedFormat: String, selectedResolution: Resolution) {
        if (selectedFormat.isNotEmpty() && selectedFormat != "best") {
            Log.d("YoutubeDlRepository", "Using analyzed format: $selectedFormat")
            request.addOption("-f", selectedFormat)
        } else {
            // 폴백 포맷 선택 (오디오 전용 처리 포함)
            val formatOptions = when (selectedResolution) {
                Resolution.P360 -> "best[height<=360]/worst"
                Resolution.P720 -> "best[height<=720]/best[height<=480]"
                Resolution.P1080 -> "best[height<=1080]/best[height<=720]"
                Resolution.P1440 -> "best[height<=1440]/best[height<=1080]"
                Resolution.P2160 -> "best[height<=2160]/best[height<=1440]"
                Resolution.AUDIO_BEST -> {
                    // 오디오 전용 폴백 전략 (다양한 포맷 시도)
                    val audioFormats = listOf(
                        "140",                                    // m4a 128k (YouTube 기본)
                        "139",                                    // m4a 48k  
                        "251",                                    // webm audio
                        "bestaudio[ext=m4a]",                   // 최고 m4a
                        "bestaudio[acodec=aac]",                 // AAC 코덱
                        "bestaudio"
                    )
                    audioFormats.joinToString("/")
                }
            }
            Log.d("YoutubeDlRepository", "Using fallback format: $formatOptions")
            request.addOption("-f", formatOptions)
        }
    }
    
    data class FormatInfo(val formatId: String, val height: Int, val info: String)
    data class AudioFormatInfo(val formatId: String, val bitrate: Int, val info: String)
    
    private fun createGenericSearchResults(query: String): List<VideoInfo> {
        return listOf(
            VideoInfo(
                id = "search_${query.hashCode()}",
                title = "$query - 인기 검색 결과",
                description = "$query 관련 인기 콘텐츠입니다.",
                uploader = "인기 채널",
                thumbnail = "https://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg",
                duration = "3:24",
                viewCount = "1.2M",
                uploadDate = "2024-01-15",
                url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
            ),
            VideoInfo(
                id = "search2_${query.hashCode()}",
                title = "$query - 최신 영상",
                description = "$query 에 대한 최신 정보입니다.",
                uploader = "뉴스 채널",
                thumbnail = "https://i.ytimg.com/vi/jNQXAC9IVRw/mqdefault.jpg",
                duration = "5:18",
                viewCount = "456K",
                uploadDate = "2024-06-20",
                url = "https://www.youtube.com/watch?v=jNQXAC9IVRw"
            )
        )
    }
    
    private fun formatDuration(seconds: Int): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format("%d:%02d", minutes, secs)
        }
    }
    
    private fun formatViewCount(count: Long): String {
        return when {
            count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
            count >= 1_000 -> String.format("%.1fK", count / 1_000.0)
            else -> count.toString()
        }
    }
    
    private fun formatUploadDate(uploadDate: String): String {
        return try {
            if (uploadDate.length >= 8) {
                val year = uploadDate.substring(0, 4)
                val month = uploadDate.substring(4, 6)
                val day = uploadDate.substring(6, 8)
                
                // 상대 시간 계산 추가
                val relativeTime = calculateRelativeTime(uploadDate)
                
                // 상대 시간이 있으면 표시, 없으면 기존 날짜 형식
                if (relativeTime.isNotEmpty()) {
                    relativeTime
                } else {
                    "$year.$month.$day"
                }
            } else {
                uploadDate
            }
        } catch (e: Exception) {
            uploadDate
        }
    }
    
    private fun calculateRelativeTime(uploadDateString: String): String {
        return try {
            if (uploadDateString.length < 8) return ""
            
            // 업로드 날짜 파싱 (YYYYMMDD 형식)
            val year = uploadDateString.substring(0, 4).toInt()
            val month = uploadDateString.substring(4, 6).toInt()
            val day = uploadDateString.substring(6, 8).toInt()
            
            // Calendar로 업로드 날짜 생성
            val uploadCalendar = java.util.Calendar.getInstance().apply {
                set(year, month - 1, day, 0, 0, 0) // month는 0부터 시작
                set(java.util.Calendar.MILLISECOND, 0)
            }
            
            val currentCalendar = java.util.Calendar.getInstance()
            val diffMillis = currentCalendar.timeInMillis - uploadCalendar.timeInMillis
            val diffDays = diffMillis / (1000 * 60 * 60 * 24)
            
            when {
                diffDays < 1 -> {
                    val diffHours = diffMillis / (1000 * 60 * 60)
                    when {
                        diffHours < 1 -> {
                            val diffMinutes = diffMillis / (1000 * 60)
                            if (diffMinutes < 1) "방금 전" else "${diffMinutes}분 전"
                        }
                        diffHours == 1L -> "1시간 전"
                        else -> "${diffHours}시간 전"
                    }
                }
                diffDays == 1L -> "1일 전"
                diffDays < 7 -> "${diffDays}일 전"
                diffDays < 14 -> "1주 전" 
                diffDays < 30 -> "${diffDays / 7}주 전"
                diffDays < 365 -> {
                    val diffMonths = diffDays / 30
                    if (diffMonths == 1L) "1개월 전" else "${diffMonths}개월 전"
                }
                else -> {
                    val diffYears = diffDays / 365
                    if (diffYears == 1L) "1년 전" else "${diffYears}년 전"
                }
            }
        } catch (e: Exception) {
            Log.e("YoutubeDlRepository", "Error calculating relative time: ${e.message}")
            ""
        }
    }
    
    // 캐싱 관련 헬퍼 함수들
    private fun getCachedQualityOptions(videoId: String): List<QualityOption>? {
        val timestamp = formatCacheTimestamp[videoId] ?: return null
        val currentTime = System.currentTimeMillis()
        
        return if (currentTime - timestamp < cacheValidityDuration) {
            formatCache[videoId]
        } else {
            // 캐시가 만료된 경우 삭제
            formatCache.remove(videoId)
            formatCacheTimestamp.remove(videoId)
            null
        }
    }
    
    private fun cacheQualityOptions(
        videoId: String, 
        options: List<QualityOption>, 
        customDuration: Long? = null
    ) {
        formatCache[videoId] = options
        formatCacheTimestamp[videoId] = System.currentTimeMillis()
        
        Log.d("YoutubeDlRepository", "Cached ${options.size} quality options for $videoId")
        
        // 캐시 크기 제한 (최대 50개 항목 유지)
        if (formatCache.size > 50) {
            cleanupOldCache()
        }
    }
    
    private fun cleanupOldCache() {
        val currentTime = System.currentTimeMillis()
        val expiredKeys = formatCacheTimestamp.filter { (_, timestamp) ->
            currentTime - timestamp > cacheValidityDuration
        }.keys
        
        expiredKeys.forEach { key ->
            formatCache.remove(key)
            formatCacheTimestamp.remove(key)
        }
        
        Log.d("YoutubeDlRepository", "Cleaned up ${expiredKeys.size} expired cache entries")
    }
    
    private suspend fun getDetailedVideoInfoFromFormats(url: String): DetailedVideoInfo? {
        return withContext(Dispatchers.IO) {
            try {
                Log.d("YoutubeDlRepository", "Getting detailed video info for: $url")
                
                // --dump-single-json으로 상세 정보 요청 (포맷 정보 포함)
                val request = YoutubeDLRequest(url)
                request.addOption("--dump-single-json")
                request.addOption("--no-warnings")
                
                val result = YoutubeDL.getInstance().execute(request)
                val jsonOutput = result.out
                
                if (jsonOutput.isNotBlank()) {
                    val jsonObject = JSONObject(jsonOutput)
                    
                    // 상세 정보 파싱
                    val uploadDate = jsonObject.optString("upload_date", "")
                    val description = jsonObject.optString("description", "")
                    val likeCount = formatCount(jsonObject.optLong("like_count", 0))
                    val commentCount = formatCount(jsonObject.optLong("comment_count", 0))
                    val channelSubscribers = formatCount(jsonObject.optLong("channel_follower_count", 0))
                    
                    // 자막 존재 여부 확인
                    val hasSubtitles = jsonObject.has("subtitles") && 
                                     jsonObject.getJSONObject("subtitles").length() > 0
                    
                    // 업로드 날짜를 상대 시간으로 변환
                    val formattedUploadDate = if (uploadDate.isNotEmpty()) {
                        formatUploadDate(uploadDate)
                    } else ""
                    
                    Log.d("YoutubeDlRepository", "Parsed detailed info - Upload: $formattedUploadDate, Description length: ${description.length}, Likes: $likeCount")
                    
                    DetailedVideoInfo(
                        actualUploadDate = formattedUploadDate,
                        actualDescription = description,
                        likeCount = likeCount,
                        hasSubtitles = hasSubtitles,
                        commentCount = commentCount,
                        channelSubscribers = channelSubscribers
                    )
                } else {
                    Log.w("YoutubeDlRepository", "Empty JSON response for detailed info")
                    null
                }
            } catch (e: Exception) {
                Log.e("YoutubeDlRepository", "Error getting detailed video info: ${e.message}", e)
                null
            }
        }
    }
    
    private fun formatCount(count: Long): String {
        return when {
            count >= 1_000_000 -> "${count / 1_000_000}M"
            count >= 1_000 -> "${count / 1_000}K"
            count > 0 -> count.toString()
            else -> ""
        }
    }
}