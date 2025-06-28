package com.example.testapp.data

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import kotlin.random.Random

/**
 * YouTube 차단 우회를 위한 헬퍼 클래스
 * 다양한 우회 전략을 제공하여 YouTube의 다운로드 차단을 우회합니다.
 */
class YouTubeBypassHelper(private val context: Context) {
    
    companion object {
        private const val TAG = "YouTubeBypassHelper"
        
        // 다양한 플랫폼의 최신 User-Agent들
        private val USER_AGENTS = listOf(
            // Android Chrome (최신)
            "Mozilla/5.0 (Linux; Android 14; SM-G998B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 13; Pixel 7 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 12; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
            
            // iPhone Safari (최신)
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_3 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.3 Mobile/15E148 Safari/604.1",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 16_7_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1",
            
            // Desktop Chrome (백업용)
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
            
            // Edge (대안)
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36 Edg/121.0.0.0"
        )
        
        // 차단 감지 키워드들
        private val BLOCKING_KEYWORDS = listOf(
            "HTTP Error 403",
            "HTTP Error 429", 
            "Sign in to confirm your age",
            "Video unavailable",
            "This video is not available",
            "blocked in your country",
            "Private video",
            "region",
            "geo-blocked",
            "unable to download video data",
            "403 Forbidden",
            "429 Too Many Requests"
        )
    }
    
    // 현재 사용 중인 우회 전략
    private var currentBypassLevel = BypassLevel.BASIC
    private var retryCount = 0
    private var lastUserAgent: String? = null
    
    enum class BypassLevel {
        BASIC,      // 기본 User-Agent 변경
        ADVANCED,   // User-Agent + 추가 헤더 + 지연
        EXTREME     // 모든 우회 기법 활용
    }
    
    data class BypassConfig(
        val useRandomUserAgent: Boolean = true,
        val addRandomHeaders: Boolean = true,
        val useRandomDelay: Boolean = true,
        val useGeoBypass: Boolean = true,
        val useProxyRotation: Boolean = false,
        val maxRetries: Int = 3
    )
    
    /**
     * 기본 우회 옵션 설정 (안전한 버전)
     */
    fun setupBasicBypass(request: YoutubeDLRequest) {
        try {
            Log.d(TAG, "Setting up basic bypass options (safe mode)")
            
            // 1. User-Agent 설정 (핵심 기능)
            val userAgent = getRandomUserAgent()
            request.addOption("--user-agent", userAgent)
            Log.d(TAG, "Using User-Agent: ${userAgent.take(50)}...")
            
            // 2. 기본 헤더 추가 (안전한 것만)
            setupBasicHeaders(request)
            
            // 3. 지역 우회 기본 설정
            request.addOption("--geo-bypass")
            
            // 4. 재시도 설정 (줄임)
            request.addOption("--retries", "3")
            request.addOption("--fragment-retries", "3")
            
        } catch (e: Exception) {
            Log.e(TAG, "Basic bypass setup failed: ${e.message}")
        }
    }
    
    /**
     * 오디오 전용 우회 옵션 설정
     */
    fun setupAudioBypass(request: YoutubeDLRequest, isRetry: Boolean = false) {
        Log.d(TAG, "Setting up audio-specific bypass options (retry: $isRetry)")
        
        // 기본 우회 적용
        setupBasicBypass(request)
        
        if (isRetry || currentBypassLevel != BypassLevel.BASIC) {
            // 재시도 시 고급 우회 전략 적용
            setupAdvancedBypass(request)
        }
        
        // 오디오 전용 최적화
        setupAudioSpecificOptions(request)
    }
    
    /**
     * 고급 우회 옵션 설정
     */
    private fun setupAdvancedBypass(request: YoutubeDLRequest) {
        Log.d(TAG, "Setting up advanced bypass options")
        
        // 1. 추가 헤더 설정
        setupAdvancedHeaders(request)
        
        // 2. 랜덤 지연 설정
        val sleepInterval = Random.nextInt(2, 6)
        request.addOption("--sleep-interval", sleepInterval.toString())
        request.addOption("--max-sleep-interval", (sleepInterval + 3).toString())
        
        // 3. 지역 우회 강화
        request.addOption("--geo-bypass-country", "US")
        request.addOption("--geo-bypass-ip-block", "0.0.0.0/0")
        
        // 4. 쿠키 처리
        setupCookieHandling(request)
        
        currentBypassLevel = BypassLevel.ADVANCED
    }
    
    /**
     * 극한 우회 옵션 설정 (최후 수단)
     */
    fun setupExtremeBypass(request: YoutubeDLRequest) {
        Log.d(TAG, "Setting up extreme bypass options")
        
        setupAdvancedBypass(request)
        
        // 1. 여러 User-Agent 시도를 위한 준비
        val userAgent = getRandomUserAgent(excludeLast = true)
        request.addOption("--user-agent", userAgent)
        
        // 2. 추가 우회 옵션
        request.addOption("--ignore-errors")
        request.addOption("--no-warnings")
        
        // 3. 포맷 선택 전략 변경 (더 관대한 선택)
        request.addOption("--format-sort", "res,ext:mp4:m4a,acodec:aac")
        
        currentBypassLevel = BypassLevel.EXTREME
    }
    
    /**
     * 랜덤 User-Agent 반환
     */
    private fun getRandomUserAgent(excludeLast: Boolean = false): String {
        val availableAgents = if (excludeLast && lastUserAgent != null) {
            USER_AGENTS.filter { it != lastUserAgent }
        } else {
            USER_AGENTS
        }
        
        val selected = availableAgents.random()
        lastUserAgent = selected
        return selected
    }
    
    /**
     * 기본 HTTP 헤더 설정
     */
    private fun setupBasicHeaders(request: YoutubeDLRequest) {
        request.addOption("--add-header", "Accept-Language:en-US,en;q=0.9,ko;q=0.8")
        request.addOption("--add-header", "Accept-Encoding:gzip, deflate, br")
        request.addOption("--add-header", "DNT:1")
        request.addOption("--add-header", "Upgrade-Insecure-Requests:1")
    }
    
    /**
     * 고급 HTTP 헤더 설정 (안전한 버전)
     */
    private fun setupAdvancedHeaders(request: YoutubeDLRequest) {
        try {
            // 필수 헤더만 추가하여 안정성 확보
            request.addOption("--add-header", "sec-ch-ua-mobile:?1")
            request.addOption("--add-header", "sec-ch-ua-platform:\"Android\"")
            
            // 랜덤 IP 헤더는 제거 (오류 원인 가능성)
            Log.d(TAG, "Applied minimal advanced headers for stability")
        } catch (e: Exception) {
            Log.w(TAG, "Advanced headers setup failed: ${e.message}")
        }
    }
    
    /**
     * 쿠키 처리 설정 (안전한 버전)
     */
    private fun setupCookieHandling(request: YoutubeDLRequest) {
        try {
            // 쿠키 사용을 비활성화하여 안정성 확보
            Log.d(TAG, "Skipping cookie setup for stability")
            // 필요한 경우에만 활성화
            // val cookieFile = File(context.cacheDir, "youtube_cookies.txt")
            // if (cookieFile.exists()) {
            //     request.addOption("--cookies", cookieFile.absolutePath)
            // }
        } catch (e: Exception) {
            Log.w(TAG, "Cookie setup skipped due to error: ${e.message}")
        }
    }
    
    /**
     * 오디오 전용 최적화 옵션 (안전한 버전)
     */
    private fun setupAudioSpecificOptions(request: YoutubeDLRequest) {
        try {
            // 기본 오디오 옵션만 추가 (충돌 방지)
            Log.d(TAG, "Audio-specific options applied (minimal for stability)")
            // 메타데이터 옵션은 제거 (오류 원인 가능성)
        } catch (e: Exception) {
            Log.w(TAG, "Audio options setup failed: ${e.message}")
        }
    }
    
    /**
     * 차단 감지 및 분석
     */
    fun detectBlocking(errorMessage: String): BlockingInfo {
        val normalizedError = errorMessage.lowercase()
        
        val blockingType = when {
            BLOCKING_KEYWORDS.any { keyword -> 
                normalizedError.contains(keyword.lowercase()) 
            } -> BlockingType.CONFIRMED
            
            normalizedError.contains("429") || 
            normalizedError.contains("too many requests") -> BlockingType.RATE_LIMITED
            
            normalizedError.contains("403") ||
            normalizedError.contains("forbidden") -> BlockingType.ACCESS_DENIED
            
            normalizedError.contains("unavailable") ||
            normalizedError.contains("private") -> BlockingType.CONTENT_RESTRICTED
            
            else -> BlockingType.UNKNOWN
        }
        
        Log.d(TAG, "Blocking detected: $blockingType for error: ${errorMessage.take(100)}")
        
        return BlockingInfo(
            isBlocked = blockingType != BlockingType.UNKNOWN,
            blockingType = blockingType,
            suggestedStrategy = getSuggestedStrategy(blockingType),
            originalError = errorMessage
        )
    }
    
    /**
     * 차단 유형에 따른 권장 전략 반환
     */
    private fun getSuggestedStrategy(blockingType: BlockingType): BypassStrategy {
        return when (blockingType) {
            BlockingType.RATE_LIMITED -> BypassStrategy.INCREASE_DELAY
            BlockingType.ACCESS_DENIED -> BypassStrategy.CHANGE_USER_AGENT
            BlockingType.CONTENT_RESTRICTED -> BypassStrategy.USE_PROXY
            BlockingType.CONFIRMED -> BypassStrategy.FULL_BYPASS
            BlockingType.UNKNOWN -> BypassStrategy.BASIC_RETRY
        }
    }
    
    /**
     * 랜덤 IP 주소 생성 (헤더용)
     */
    private fun generateRandomIP(): String {
        return "${Random.nextInt(1, 255)}.${Random.nextInt(1, 255)}.${Random.nextInt(1, 255)}.${Random.nextInt(1, 255)}"
    }
    
    /**
     * 기본 쿠키 파일 생성
     */
    private fun createDefaultCookies(cookieFile: File) {
        try {
            cookieFile.writeText("""
                # YouTube 기본 쿠키
                .youtube.com	TRUE	/	FALSE	${System.currentTimeMillis() / 1000 + 86400}	CONSENT	YES+cb
                .youtube.com	TRUE	/	FALSE	${System.currentTimeMillis() / 1000 + 86400}	GPS	1
                .youtube.com	TRUE	/	FALSE	${System.currentTimeMillis() / 1000 + 86400}	YSC	${generateRandomString(20)}
                .youtube.com	TRUE	/	FALSE	${System.currentTimeMillis() / 1000 + 86400}	VISITOR_INFO1_LIVE	${generateRandomString(22)}
            """.trimIndent())
            Log.d(TAG, "Created default cookie file")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create cookie file: ${e.message}")
        }
    }
    
    /**
     * 랜덤 문자열 생성
     */
    private fun generateRandomString(length: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..length)
            .map { chars.random() }
            .joinToString("")
    }
    
    /**
     * 재시도 카운터 증가 및 전략 업그레이드
     */
    fun incrementRetryAndUpgrade(): Boolean {
        retryCount++
        
        return when {
            retryCount <= 2 -> {
                currentBypassLevel = BypassLevel.ADVANCED
                true
            }
            retryCount <= 4 -> {
                currentBypassLevel = BypassLevel.EXTREME
                true
            }
            else -> {
                false // 최대 재시도 초과
            }
        }
    }
    
    /**
     * 재시도 상태 초기화
     */
    fun resetRetryState() {
        retryCount = 0
        currentBypassLevel = BypassLevel.BASIC
        lastUserAgent = null
    }
    
    /**
     * 현재 우회 상태 정보
     */
    fun getBypassStatus(): BypassStatus {
        return BypassStatus(
            level = currentBypassLevel,
            retryCount = retryCount,
            lastUserAgent = lastUserAgent?.take(50)
        )
    }
    
    // 데이터 클래스들
    data class BlockingInfo(
        val isBlocked: Boolean,
        val blockingType: BlockingType,
        val suggestedStrategy: BypassStrategy,
        val originalError: String
    )
    
    data class BypassStatus(
        val level: BypassLevel,
        val retryCount: Int,
        val lastUserAgent: String?
    )
    
    enum class BlockingType {
        CONFIRMED,           // 확실한 차단
        RATE_LIMITED,        // 요청 빈도 제한
        ACCESS_DENIED,       // 접근 거부
        CONTENT_RESTRICTED,  // 콘텐츠 제한
        UNKNOWN             // 알 수 없는 오류
    }
    
    enum class BypassStrategy {
        BASIC_RETRY,        // 기본 재시도
        CHANGE_USER_AGENT,  // User-Agent 변경
        INCREASE_DELAY,     // 지연 시간 증가
        USE_PROXY,          // 프록시 사용
        FULL_BYPASS        // 모든 우회 기법 적용
    }
}