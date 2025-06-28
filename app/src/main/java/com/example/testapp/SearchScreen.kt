package com.example.testapp

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.testapp.data.VideoInfo
import com.example.testapp.data.DownloadStatus
import com.example.testapp.data.Resolution
import com.example.testapp.data.QualityOption
import com.example.testapp.data.DownloadPhase
import com.example.testapp.data.DownloadErrorType
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    modifier: Modifier = Modifier,
    viewModel: MainViewModel = viewModel(),
    onRequestPermissions: () -> Unit = {}
) {
    val context = LocalContext.current
    val searchText by viewModel.searchText.collectAsState()
    val searchState by viewModel.searchState.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val qualityState by viewModel.qualityState.collectAsState()
    val keyboardController = LocalSoftwareKeyboardController.current
    
    // Permission management
    val permissionState by PermissionHelper.permissionState.collectAsState()
    
    var pendingDownloadVideo by remember { mutableStateOf<VideoInfo?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 검색 결과가 있을 때만 뒤로가기 버튼과 축소된 레이아웃 표시
        if (searchState.videos.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { 
                        viewModel.clearSearch()
                        keyboardController?.hide()
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "뒤로가기",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                
                Text(
                    text = "Kerid Tubedown",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
                
                // 오른쪽 공간을 맞추기 위한 빈 공간
                Spacer(modifier = Modifier.width(48.dp))
            }
            Spacer(modifier = Modifier.height(16.dp))
        } else {
            // 초기 화면 - 큰 제목과 충분한 여백
            Spacer(modifier = Modifier.height(80.dp))
            
            Text(
                text = "Kerid Tubedown",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "YouTube 영상을 간편하게 다운로드하세요",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(40.dp))
        }
        
        // 검색 입력 필드
        OutlinedTextField(
            value = searchText,
            onValueChange = viewModel::updateSearchText,
            label = { Text("검색어를 입력하세요") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "검색"
                )
            },
            trailingIcon = {
                if (searchState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                }
            },
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Search
            ),
            keyboardActions = KeyboardActions(
                onSearch = {
                    viewModel.performSearch()
                    keyboardController?.hide()
                }
            ),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // 검색 결과 표시
        when {
            searchState.isLoading -> {
                Spacer(modifier = Modifier.height(40.dp))
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "검색 중...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            searchState.error != null -> {
                Spacer(modifier = Modifier.height(20.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = "검색 중 오류가 발생했습니다: ${searchState.error}",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
            
            searchState.videos.isNotEmpty() -> {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(searchState.videos) { video ->
                        VideoItem(
                            video = video,
                            downloadProgress = downloadProgress[video.id],
                            onDownloadClick = {
                                if (PermissionHelper.hasAllRequiredPermissions()) {
                                    viewModel.showQualitySelector(video)
                                } else {
                                    pendingDownloadVideo = video
                                    onRequestPermissions()
                                }
                            }
                        )
                    }
                }
            }
            
            searchText.isNotEmpty() -> {
                Spacer(modifier = Modifier.height(40.dp))
                Text(
                    text = "검색 결과가 없습니다",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        
        // Permission request UI
        if (!PermissionHelper.hasAllRequiredPermissions() && searchState.videos.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "권한 상태",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    
                    if (!permissionState.hasBasicPermissions) {
                        Text(
                            text = "기본 미디어 권한이 필요합니다",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    
                    if (!permissionState.hasAllFilesAccess) {
                        Text(
                            text = "파일 관리 권한이 필요합니다 (Movies 폴더 사용)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    
                    Button(
                        onClick = onRequestPermissions,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Text("권한 설정")
                    }
                }
            }
        }
        
        // Handle permission completion with pending download
        LaunchedEffect(permissionState.hasBasicPermissions, permissionState.hasAllFilesAccess) {
            if (PermissionHelper.hasAllRequiredPermissions() && pendingDownloadVideo != null) {
                val video = pendingDownloadVideo!!
                pendingDownloadVideo = null
                viewModel.showQualitySelector(video)
            }
        }
        
        // Quality Selection Dialog
        qualityState.videoInfo?.let { videoInfo ->
            QualitySelectionDialog(
                qualityState = qualityState,
                onQualitySelected = { resolution ->
                    viewModel.downloadVideoWithQuality(videoInfo, resolution)
                },
                onDismiss = {
                    viewModel.hideQualitySelector()
                }
            )
        }
    }
}

fun openFile(context: Context, filePath: String) {
    try {
        val file = java.io.File(filePath)
        android.util.Log.d("SearchScreen", "Opening file: $filePath")
        android.util.Log.d("SearchScreen", "File exists: ${file.exists()}")
        
        if (file.exists()) {
            try {
                // 파일 직접 열기 시도
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
                // 파일 확장자에 따라 MIME 타입 결정
                val mimeType = when (file.extension.lowercase()) {
                    "m4a", "mp3", "aac", "wav", "flac" -> "audio/*"
                    else -> "video/*"
                }
                intent.setDataAndType(uri, mimeType)
                intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                android.util.Log.d("SearchScreen", "File opened successfully")
            } catch (e: Exception) {
                android.util.Log.e("SearchScreen", "Failed to open file: ${e.message}")
                // 파일 열기 실패 시 폴더 열기
                openFolder(context, file.parentFile?.absolutePath ?: filePath)
            }
        } else {
            android.util.Log.e("SearchScreen", "File not found: $filePath")
            android.widget.Toast.makeText(context, "파일을 찾을 수 없습니다", android.widget.Toast.LENGTH_SHORT).show()
        }
    } catch (e: Exception) {
        android.util.Log.e("SearchScreen", "Exception in openFile: ${e.message}", e)
        android.widget.Toast.makeText(context, "파일을 열 수 없습니다: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
    }
}

fun openFolder(context: Context, folderPath: String) {
    try {
        android.util.Log.d("SearchScreen", "Opening folder: $folderPath")
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
        val uri = android.net.Uri.parse("content://com.android.externalstorage.documents/document/primary:Movies")
        intent.setDataAndType(uri, "resource/folder")
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        
        try {
            context.startActivity(intent)
            android.widget.Toast.makeText(context, "다운로드 폴더를 열었습니다", android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            // 폴더 열기 실패시 파일 매니저로 대체
            val fallbackIntent = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT)
            fallbackIntent.type = "*/*"
            fallbackIntent.addCategory(android.content.Intent.CATEGORY_OPENABLE)
            context.startActivity(android.content.Intent.createChooser(fallbackIntent, "파일 매니저 열기"))
            android.widget.Toast.makeText(context, "파일 매니저를 열어 Movies 폴더를 확인하세요", android.widget.Toast.LENGTH_LONG).show()
        }
    } catch (e: Exception) {
        android.util.Log.e("SearchScreen", "Exception in openFolder: ${e.message}", e)
        android.widget.Toast.makeText(context, "폴더를 열 수 없습니다", android.widget.Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun VideoItem(
    video: VideoInfo,
    downloadProgress: com.example.testapp.data.DownloadProgress? = null,
    onDownloadClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        // 썸네일 (전체 폭 사용, YouTube 앱 스타일)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(MaterialTheme.shapes.medium)
        ) {
            AsyncImage(
                model = video.thumbnail,
                contentDescription = "썸네일",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                placeholder = painterResource(android.R.drawable.ic_menu_gallery),
                error = painterResource(android.R.drawable.ic_menu_gallery)
            )
            
            // 영상 길이 표시
            if (video.duration.isNotEmpty()) {
                Card(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
                    )
                ) {
                    Text(
                        text = video.duration,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        
        // 제목과 다운로드 버튼을 한 행에 배치
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            // 제목과 설명 (왼쪽)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = video.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                // 채널, 조회수, 업로드 날짜 (컴팩트하게)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = video.uploader,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, false)
                    )
                    
                    if (video.viewCount.isNotEmpty()) {
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = video.viewCount,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    if (video.formattedUploadDate.isNotEmpty()) {
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = video.formattedUploadDate,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            // 다운로드 버튼/상태 (오른쪽)
            when (downloadProgress?.status) {
                DownloadStatus.PREPARING -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = "준비중",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                DownloadStatus.DOWNLOADING -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        CircularProgressIndicator(
                            progress = { downloadProgress.progress / 100f },
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = "${downloadProgress.progress.toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        
                        // 단계별 상태 표시
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = downloadProgress.currentPhase.emoji,
                                style = MaterialTheme.typography.labelSmall
                            )
                            Text(
                                text = downloadProgress.currentPhase.displayName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        
                        // 선택된 화질 표시
                        downloadProgress.selectedResolution?.let { resolution ->
                            Text(
                                text = resolution.displayName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
                DownloadStatus.COMPLETED -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        IconButton(
                            onClick = {
                                downloadProgress.filePath?.let { filePath ->
                                    openFile(context, filePath)
                                }
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.FileOpen,
                                contentDescription = "Open File",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Text(
                            text = "열기",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                DownloadStatus.ERROR -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        IconButton(
                            onClick = onDownloadClick,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = "Retry Download",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Text(
                            text = "재시도",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        
                        // 사용자 친화적 오류 메시지 표시
                        downloadProgress.errorType?.let { errorType ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(top = 4.dp)
                            ) {
                                Text(
                                    text = errorType.emoji,
                                    style = MaterialTheme.typography.labelSmall
                                )
                                Text(
                                    text = errorType.userMessage,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
                else -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        IconButton(
                            onClick = onDownloadClick,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = "Download",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Text(
                            text = "저장",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QualitySelectionDialog(
    qualityState: QualityState,
    onQualitySelected: (Resolution) -> Unit,
    onDismiss: () -> Unit
) {
    val videoInfo = qualityState.videoInfo ?: return
    
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 600.dp)
            ) {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    item {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // 비디오 제목
                            Text(
                                text = videoInfo.title,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            // 업로더, 업로드 날짜, 자막 정보 (컴팩트)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = videoInfo.uploader,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f, false)
                                )
                                
                                // 상세 정보의 업로드 날짜 우선 사용
                                val uploadDate = qualityState.detailedInfo?.actualUploadDate?.takeIf { it.isNotEmpty() }
                                    ?: videoInfo.formattedUploadDate
                                
                                if (uploadDate.isNotEmpty()) {
                                    Text(
                                        text = "•",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = uploadDate,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                
                                // 자막 여부만 표시
                                qualityState.detailedInfo?.let { detailedInfo ->
                                    if (detailedInfo.hasSubtitles) {
                                        Text(
                                            text = "•",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = "🔤 자막",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            // 영상 설명 (컴팩트, 3줄 제한)
                            val description = qualityState.detailedInfo?.actualDescription?.takeIf { it.isNotEmpty() }
                                ?: videoInfo.description
                            
                            if (description.isNotEmpty()) {
                                Text(
                                    text = "설명",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 60.dp)
                                ) {
                                    LazyColumn {
                                        item {
                                            Text(
                                                text = description,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                lineHeight = 18.sp,
                                                fontSize = 13.sp
                                            )
                                        }
                                    }
                                }
                                
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                            
                            Spacer(modifier = Modifier.height(4.dp))
                            
                            when {
                                qualityState.isLoading -> {
                                    // 로딩 중 표시
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(32.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(16.dp)
                                        ) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(32.dp),
                                                strokeWidth = 3.dp
                                            )
                                            Text(
                                                text = "사용 가능한 화질을 확인하는 중...",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                                
                                qualityState.error != null -> {
                                    // 에러 시 기본 옵션과 에러 메시지 표시
                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Card(
                                            colors = CardDefaults.cardColors(
                                                containerColor = MaterialTheme.colorScheme.errorContainer
                                            )
                                        ) {
                                            Text(
                                                text = "⚠️ ${qualityState.error}\n기본 화질 옵션을 사용합니다.",
                                                modifier = Modifier.padding(12.dp),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onErrorContainer
                                            )
                                        }
                                        
                                        Spacer(modifier = Modifier.height(8.dp))
                                        
                                        // 기본 화질 옵션들
                                        listOf(Resolution.P360, Resolution.P720, Resolution.P1080).forEach { resolution ->
                                            QualityOptionItem(
                                                resolution = resolution,
                                                onClick = { onQualitySelected(resolution) }
                                            )
                                        }
                                        
                                        // 오디오 다운로드 옵션
                                        QualityOptionItem(
                                            resolution = Resolution.AUDIO_BEST,
                                            onClick = { onQualitySelected(Resolution.AUDIO_BEST) }
                                        )
                                    }
                                }
                                
                                qualityState.options.isNotEmpty() -> {
                                    // 화질 선택 (컴팩트)
                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = "다운로드 화질 선택",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontWeight = FontWeight.Medium
                                        )
                                        
                                        Spacer(modifier = Modifier.height(4.dp))
                                        
                                        // 비디오 화질 옵션들
                                        qualityState.options.forEach { option ->
                                            QualityOptionItem(
                                                qualityOption = option,
                                                onClick = { onQualitySelected(option.resolution) }
                                            )
                                        }
                                        
                                        // 오디오 다운로드 옵션
                                        QualityOptionItem(
                                            resolution = Resolution.AUDIO_BEST,
                                            onClick = { onQualitySelected(Resolution.AUDIO_BEST) }
                                        )
                                    }
                                }
                                
                                else -> {
                                    // 예상치 못한 상황: 기본 옵션 표시
                                    Text(
                                        text = "화질 정보를 가져올 수 없어 기본 옵션을 표시합니다.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    
                                    Spacer(modifier = Modifier.height(8.dp))
                                    
                                    // 기본 화질 옵션들
                                    listOf(Resolution.P360, Resolution.P720, Resolution.P1080).forEach { resolution ->
                                        QualityOptionItem(
                                            resolution = resolution,
                                            onClick = { onQualitySelected(resolution) }
                                        )
                                    }
                                    
                                    // 오디오 다운로드 옵션
                                    QualityOptionItem(
                                        resolution = Resolution.AUDIO_BEST,
                                        onClick = { onQualitySelected(Resolution.AUDIO_BEST) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("취소")
            }
        }
    )
}

@Composable
fun QualityOptionItem(
    qualityOption: QualityOption? = null,
    resolution: Resolution? = null,
    onClick: () -> Unit
) {
    val displayResolution = qualityOption?.resolution ?: resolution!!
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 해상도 아이콘
                Text(
                    text = displayResolution.emoji,
                    style = MaterialTheme.typography.titleMedium
                )
                
                // 해상도 이름과 크기 정보
                Column {
                    Text(
                        text = displayResolution.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    
                    if (qualityOption != null) {
                        Text(
                            text = qualityOption.estimatedSize,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}