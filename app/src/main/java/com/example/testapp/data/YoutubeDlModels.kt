package com.example.testapp.data

data class VideoInfo(
    val id: String,
    val title: String,
    val description: String,
    val uploader: String,
    val thumbnail: String,
    val duration: String,
    val viewCount: String,
    val uploadDate: String,
    val url: String,
    val formatId: String = "",
    val ext: String = "",
    val filesize: Long = 0L,
    val availableResolutions: List<Resolution> = emptyList(),
    val maxResolution: Resolution? = null,
    val formattedUploadDate: String = ""
)

data class DownloadProgress(
    val videoId: String,
    val progress: Float,
    val status: DownloadStatus,
    val etaSeconds: Long = 0L,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val error: String? = null,
    val filePath: String? = null,
    val startTime: Long = 0L,
    val currentPhase: DownloadPhase = DownloadPhase.PREPARING,
    val phaseProgress: Float = 0f,
    val selectedResolution: Resolution? = null,
    val errorType: DownloadErrorType? = null
)

enum class Resolution(val displayName: String, val height: Int, val emoji: String) {
    P360("360p", 360, "📱"),
    P720("720p", 720, "📺"), 
    P1080("1080p", 1080, "🎬"),
    P1440("1440p (2K)", 1440, "✨"),
    P2160("4K", 2160, "🏆"),
    AUDIO_BEST("최고음질 오디오", 0, "🎵");
    
    companion object {
        fun fromHeight(height: Int): Resolution? {
            return values().find { it.height == height }
        }
        
        fun fromString(resolution: String): Resolution? {
            return when {
                resolution.contains("2160") || resolution.contains("4K") -> P2160
                resolution.contains("1440") || resolution.contains("2K") -> P1440  
                resolution.contains("1080") -> P1080
                resolution.contains("720") -> P720
                resolution.contains("360") -> P360
                else -> null
            }
        }
    }
}

enum class DownloadPhase(val displayName: String, val emoji: String) {
    PREPARING("준비 중", "⏳"),
    ANALYZING_FORMAT("포맷 분석 중", "🔍"),
    VIDEO("비디오 다운로드", "📹"),
    AUDIO("오디오 다운로드", "🎵"),
    MERGING("병합 중", "⚙️"),
    COMPLETED("완료", "✅"),
    ERROR("오류", "❌")
}

enum class DownloadStatus {
    IDLE,
    PREPARING,
    DOWNLOADING,
    COMPLETED,
    ERROR,
    CANCELLED
}

enum class DownloadErrorType(val userMessage: String, val emoji: String) {
    GOOGLE_BLOCKED("구글에서 차단되었습니다", "🚫"),
    FORMAT_NOT_AVAILABLE("요청한 화질을 찾을 수 없습니다", "❓"),
    NETWORK_ERROR("네트워크 연결 오류입니다", "📡"),
    PERMISSION_ERROR("파일 저장 권한이 필요합니다", "🔒"),
    UNKNOWN_ERROR("알 수 없는 오류가 발생했습니다", "⚠️")
}

data class QualityOption(
    val resolution: Resolution,
    val videoFormat: String,
    val audioFormat: String,
    val estimatedSize: String,
    val isRecommended: Boolean = false
)

data class SearchResult(
    val query: String,
    val videos: List<VideoInfo>
)

data class DetailedVideoInfo(
    val actualUploadDate: String = "",
    val actualDescription: String = "",
    val likeCount: String = "",
    val hasSubtitles: Boolean = false,
    val commentCount: String = "",
    val channelSubscribers: String = ""
)

