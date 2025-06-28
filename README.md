# Kerid Tubedown

Android YouTube 비디오 및 오디오 다운로더 앱

## 기능

- YouTube 영상 검색
- 다양한 화질 옵션 (360p, 720p, 1080p, 1440p, 4K)
- 고품질 오디오 다운로드 (m4a 형식)
- 비디오는 Movies 폴더에 저장
- 오디오는 Music 폴더에 저장
- Material Design 3 UI
- 다운로드 진행률 표시

## 기술 스택

- **언어**: Kotlin
- **UI**: Jetpack Compose
- **아키텍처**: MVVM + StateFlow
- **다운로드**: yt-dlp Android wrapper
- **이미지 로딩**: Coil
- **Material Design**: Material 3

## 시스템 요구사항

- Android 7.0 (API 24) 이상
- 저장소 권한 필요
- 인터넷 연결 필요

## 설치

1. 저장소 클론
2. Android Studio에서 프로젝트 열기
3. 에뮬레이터 또는 실제 기기에서 실행

## 사용법

1. 앱 실행 후 검색어 입력
2. 원하는 영상 선택
3. 화질/오디오 옵션 선택
4. 다운로드 완료 후 파일 열기

## 폴더 구조

```
app/src/main/java/com/example/testapp/
├── MainActivity.kt          # 메인 액티비티
├── MainViewModel.kt         # 메인 뷰모델
├── SearchScreen.kt          # 검색 및 UI 화면
├── PermissionHelper.kt      # 권한 관리
└── data/
    ├── YoutubeDlRepository.kt    # 다운로드 로직
    ├── YoutubeDlModels.kt       # 데이터 모델
    └── YouTubeBypassHelper.kt   # YouTube 우회 (비활성화됨)
```

## 주요 수정사항

- YouTube 우회 시스템 비활성화 (안정성 개선)
- 오디오 다운로드 기능 추가
- UI 간소화 (탭 제거, 통합 품질 선택)
- 폴더 구조 개선 (TestApp 하위폴더 제거)

## 라이선스

개인 프로젝트