package com.example.testapp

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.testapp.data.YoutubeDlRepository
import com.example.testapp.data.VideoInfo
import com.example.testapp.data.DownloadProgress
import com.example.testapp.data.Resolution
import com.example.testapp.data.QualityOption
import com.example.testapp.data.DetailedVideoInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SearchState(
    val isLoading: Boolean = false,
    val videos: List<VideoInfo> = emptyList(),
    val error: String? = null
)

data class QualityState(
    val isLoading: Boolean = false,
    val options: List<QualityOption> = emptyList(),
    val error: String? = null,
    val videoInfo: VideoInfo? = null,
    val detailedInfo: DetailedVideoInfo? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = YoutubeDlRepository(application.applicationContext)
    
    private val _searchText = MutableStateFlow("")
    val searchText: StateFlow<String> = _searchText.asStateFlow()
    
    private val _searchState = MutableStateFlow(SearchState())
    val searchState: StateFlow<SearchState> = _searchState.asStateFlow()
    
    private val _downloadProgress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, DownloadProgress>> = _downloadProgress.asStateFlow()
    
    private val _qualityState = MutableStateFlow(QualityState())
    val qualityState: StateFlow<QualityState> = _qualityState.asStateFlow()
    
    fun updateSearchText(text: String) {
        _searchText.value = text
    }
    
    fun clearSearch() {
        _searchText.value = ""
        _searchState.value = SearchState()
        _downloadProgress.value = emptyMap()
    }
    
    fun performSearch() {
        val query = _searchText.value.trim()
        if (query.isEmpty()) return
        
        viewModelScope.launch {
            _searchState.value = _searchState.value.copy(isLoading = true, error = null)
            
            repository.searchVideos(query)
                .onSuccess { videos ->
                    _searchState.value = _searchState.value.copy(
                        isLoading = false,
                        videos = videos,
                        error = null
                    )
                }
                .onFailure { exception ->
                    _searchState.value = _searchState.value.copy(
                        isLoading = false,
                        videos = emptyList(),
                        error = exception.message
                    )
                }
        }
    }
    
    fun showQualitySelector(videoInfo: VideoInfo) {
        // 즉시 로딩 상태로 설정
        _qualityState.value = QualityState(
            isLoading = true,
            videoInfo = videoInfo
        )
        
        // 백그라운드에서 화질 옵션 및 상세 정보 로드
        viewModelScope.launch {
            try {
                val (options, detailedInfo) = repository.getQualityOptionsForVideo(videoInfo)
                _qualityState.value = QualityState(
                    isLoading = false,
                    options = options,
                    videoInfo = videoInfo,
                    detailedInfo = detailedInfo
                )
            } catch (e: Exception) {
                _qualityState.value = QualityState(
                    isLoading = false,
                    error = e.message ?: "화질 정보를 가져올 수 없습니다",
                    videoInfo = videoInfo
                )
            }
        }
    }
    
    fun hideQualitySelector() {
        _qualityState.value = QualityState()
    }
    
    fun downloadVideoWithQuality(videoInfo: VideoInfo, resolution: Resolution) {
        hideQualitySelector()
        
        // 다운로드 시작 시간 기록
        val startTime = System.currentTimeMillis()
        
        // 즉시 PREPARING 상태로 설정
        val preparingProgress = DownloadProgress(
            videoId = videoInfo.id,
            progress = 0f,
            status = com.example.testapp.data.DownloadStatus.PREPARING,
            startTime = startTime,
            selectedResolution = resolution
        )
        val currentMap = _downloadProgress.value.toMutableMap()
        currentMap[videoInfo.id] = preparingProgress
        _downloadProgress.value = currentMap
        
        viewModelScope.launch {
            repository.downloadVideoWithQuality(videoInfo, resolution) { progress ->
                val updatedMap = _downloadProgress.value.toMutableMap()
                updatedMap[videoInfo.id] = progress
                _downloadProgress.value = updatedMap
            }
        }
    }
    
    fun downloadVideo(videoInfo: VideoInfo) {
        // 기본 720p로 다운로드 (기존 호환성을 위해)
        downloadVideoWithQuality(videoInfo, Resolution.P720)
    }
}