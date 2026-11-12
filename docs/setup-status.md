# 준비 상태 (SDK·에뮬레이터·외부 자료)

계획 Task 0에서 사용자가 준비한 것과, 에이전트가 직접 호출해 확인한 결과를 적는다. 키 값은 적지 않는다(`backend/.env`, 커밋 안 함).

- 마지막 확인: 2026-11-12

## 1. Pleos SDK·에뮬레이터

| 항목 | 상태 |
|---|---|
| Pleos Playground 개발자 등록, 프로젝트 | 완료. 프로젝트 `WiperNet`, 분류 "모빌리티 서비스". 클라이언트 ID·시크릿은 여기 적지 않는다 |
| Pleos Connect Emulator v2.1.0 설치, Virtual Console 접속 | 완료. `adb forward tcp:8088 tcp:8088` 뒤 http://127.0.0.1:8088/ 응답 200 |
| 공모전 문의 | 공모전 사이트에 게시판이 없다. 문의는 이메일 `appdevchallenge@hyundai-partners.com`. Vehicle Control 공개 시기와 대체 시연 허용 여부를 문의한다(2026-11-10) |

### SDK 상태와 에뮬레이터 확인 결과 (2026-11-10)

Playground 상태는 프로젝트 SDK 메뉴에서 본 값이다. 신청 버튼이 있는 SDK는 없었다. 공모전 사이트(공지 3건, FAQ)와 Pleos 개발자 사이트에 공개 시기 공지는 없다. FAQ는 "각 SDK의 현재 제공 상태와 사용 조건은 SDK 문서 페이지에서 확인"이라고만 한다.

에뮬레이터 결과는 저장소 밖 임시 앱으로 SDK를 직접 불러 본 것이다. 앱 manifest에 `pleos.car.permission.CAR_WINDOWS`, `CAR_DRIVE`, `CAR_LIGHTS`, `CAR_CLIMATE`, `CONTROL_CAR_WINDOWS`, `CONTROL_CAR_LIGHTS`, `CONTROL_CAR_CLIMATE`, `TTS_SERVICE`, `STT_SERVICE`, `android.permission.RECORD_AUDIO`를 넣고 `adb install -g`로 모두 허용했다.

| SDK (좌표) | Playground | 에뮬레이터 결과 |
|---|---|---|
| Vehicle State (`ai.pleos.playground:Vehicle:2.3.2`) | 11개 항목 모두 사용 가능. 와이퍼 모드는 "창문 상태 조회"에 들어 있다 | 와이퍼: 스위치 capability `READ_WRITE`, 앞·뒤 유리 모두 값 목록 `OFF, CONTINUOUS_LEVEL_1, CONTINUOUS_LEVEL_3, AUTO`. 상태 capability `READ`, 값 목록 `OFF, ON, SERVICE`. 현재 값 읽기와 상태 리스너가 동작한다. 기어: `GEAR_PARK`를 읽었고 리스너가 동작한다 |
| Vehicle Control (같은 SDK의 set API) | 8개 항목 모두 준비 중 | **Virtual Console이 브라우저에 열려 있을 때만 반영된다.** 열려 있으면 운전석 창문(7→3→0)·하향등 스위치(ON↔OFF)·앞유리 디프로스터(켜기·끄기)가 앱에서 다시 읽은 값, 앱의 변경 리스너, Virtual Console 상태(`/vehicle/state`) 모두에 1초 안에 반영된다. 닫혀 있으면 성공 콜백은 오지만 값이 그대로다(처음 확인에서 "반영 안 됨"으로 적은 것은 이 경우였다). 범위를 넘는 값은 `onFailed`로 온다. 공조 전원(`setHvacPower`)은 운전석 영역(`ROW_1_LEFT`)을 지원하지 않는다고 실패하고, 디프로스터는 공조 전원 없이도 동작한다 |
| NaviHelper (`ai.pleos.playground:NaviHelper:3.0.5`) | 사용 가능 | **연결·조회·경로 요청이 동작한다(2026-11-11).** 권한 `NAVI_ROUTE`(조회), `NAVI_ROUTE_SEARCH`(경로 요청). `initialize(onConnected, onFailed)` 뒤 리스너를 등록했다. (1) `requestRoute(RouteInfo(...))` → `onRouteStarted`(거리 10,000 m, 12분), 바로 뒤 `onRouteEnded`가 한 번 오고, `onNavigationStatus(ROUTE_DRIVE)`, `RouteStateInfo.naviMode`는 경로 없을 때 `SAFE_GUIDING`, 안내 중 `ROUTE_GUIDING`. (2) **`onTBTInfo`는 경로 전체의 안내 지점 목록을 준다**(이번 경로 6개). 지하차도 진입은 `TURN_ENTER_UNDER_L`로 왔고 설명은 방면 글자("남부순환로,성수대교 방면")라 어느 지하차도인지는 알려 주지 않는다. 거리는 미터이고 **앞 안내 지점부터의 구간 거리**다: 80+390+1100+7400+580+370 = 9,920 m가 경로 10 km와 맞고, 내비 화면도 다음 안내 80 m, 그다음 안내 390 m로 보인다. (3) **내비가 쓰는 현재 위치는 에뮬레이터 기본 위치(성남 수정구 시흥동 37.4127, 127.0947)에 고정이다.** `adb emu geo fix`로 Android GPS를 경로를 따라 옮겨도(`dumpsys location`에 반영됨), Virtual Console에서 기어 D·60 km/h를 줘도 내비 위치와 TBT 거리가 바뀌지 않는다. 내비 설정에 모의 주행 항목이 없다(내비 3.3.2-rc5-hotfix.3). 그래서 주행 중 TBT 거리가 줄어드는 모습과 재탐색은 에뮬레이터에서 확인할 수 없다 |
| TextToSpeech (`ai.pleos.playground:TextToSpeech:3.3.1`) | 준비 중 | **`ON_DEVICE` 모드가 동작한다.** 한국어 문장을 `onStart` → `onDone`까지 재생했고 이어폰으로 들린다. 화자는 `seoyoon`. `initialize()` 성공 뒤에 `addEventListener()`를 불러야 한다(먼저 부르면 `UninitializedPropertyAccessException`으로 앱이 죽는다) |
| SpeechToText (`ai.pleos.playground:SpeechToText:3.3.1`) | 준비 중 | **`ON_DEVICE` 한국어 인식이 동작한다.** `setLocale(Locale.KOREA)` 뒤 `request()`를 열 번 이어 불러 "그만", "알았어", "네", "아니요", "그만해", "그만 말해", "적당히 해", "그만", "알았어", "네"를 모두 정확히 받았다. 말이 끝나고 약 0.5초 뒤 최종 글자가 온다. 알게 된 것: (1) 말이 없으면 인식이 스스로 끝나지 않는다(1분 넘게 듣기만 했다). 앱이 시간을 재고 `stop()`을 불러야 한다. (2) 최종 글자 `onUpdated(text, completed = true)`는 `onEndedRecognition` 직후에 온다. (3) `request()`마다 `onReady`가 다시 오고, 중간 글자마다 `onStartedRecognition`이 다시 온다. (4) 시스템 로그에 녹음 설정이 `ns:false, aec:false`(잡음·에코 제거 꺼짐)로 나와, 안내 음성이 끝난 뒤에 들어야 한다. (5) 호스트 마이크는 Extended Controls → Microphone 설정을 켠 뒤에도 소리가 들어오지 않았고, `adb emu avd hostmicon`을 보낸 뒤 들어왔다(설정이 늦게 반영됐을 수도 있다). 리스너는 TTS와 같이 `initialize()` 성공 뒤에 등록했다 |
| FusedLocation (`ai.pleos.playground:FusedLocation:1.1.3`) | 준비 중 | **동작한다.** 권한은 `pleos.car.permission.FUSED_LOCATION`(dangerous). `isSupported()`가 true, `getLastKnownLocation()`과 `registerFusedLocationCallback()`이 약 1초마다 위치를 준다. `adb emu geo fix 127.0300 37.5000`으로 바꾼 위치가 바로 들어온다. 처음 값은 에뮬레이터 기본 위치(37.422, -122.084)다. 위치만 바꾸면 `heading`은 0으로 온다 |

**사용 결정 (사용자, 2026-11-10):** 에뮬레이터에서 동작하는 Vehicle 읽기, NaviHelper, FusedLocation, TTS, STT는 Playground 표시와 관계없이 쓴다. Vehicle Control(set API)도 쓴다. set 성공 콜백만으로는 반영을 알 수 없으므로 다시 읽거나 변경 이벤트로 확인하고, 반영되지 않은 항목은 음성으로 권한다.

SDK 저장소는 `https://nexus-playground.pleos.ai/repository/maven-releases/`이고 인증이 필요 없다.

### 앱 코드로 확인한 차량 신호와 업로드 (2026-11-12, Task 13)

디버그 빌드에만 있는 확인 화면 `SignalCheckActivity`로 앱의 `PleosVehicleSignals`, `UploadQueue`, `LiveHazardClient`를 그대로 돌렸다. 띄우는 명령과 설정 값은 그 파일 머리 주석에 있다.

- **서버 주소:** 이 맥은 다른 Docker 컨테이너가 8000번을 쓰고 있어 서버를 `--host 0.0.0.0 --port 8001`로 띄우고 앱 서버 주소를 `http://10.0.2.2:8001`로 바꿨다. 서버 시계가 비어 있으면 앱이 이벤트를 보내지 않으므로, 먼저 `curl`로 이벤트 하나를 넣어 시계를 세웠다.
- **앱 manifest:** 서버가 `http`라 `INTERNET` 권한과 `usesCleartextTraffic="true"`를 넣었다.
- **Virtual Console을 브라우저 없이 조작할 수 있다.** 화면이 쓰는 HTTP를 그대로 부른다. `POST /browser/session?client=<아무 이름>`을 1초마다 보내면 브라우저가 열린 것으로 친다. 그 상태에서 `PUT /vehicle/body?front_wiper=Low`(값 `Off, Auto, Low, High`)로 앞 와이퍼를 바꾼다. 하트비트를 시작하자마자 바꾸면 반영되지 않았고, 몇 초 뒤에는 반영됐다.
- **와이퍼 값:** Low → `CONTINUOUS_LEVEL_1/ON`, High → `CONTINUOUS_LEVEL_3/ON`, Auto → `AUTO/ON`, Off → `OFF/OFF`. 바꿀 때마다 로컬 서버 `/v1/debug/vehicles`에 몇 초 안에 그 값이 왔다. 상태 리스너가 스위치보다 약 50 ms 먼저 와서, 상태만 고치면 `OFF/ON`, `AUTO/OFF` 같은 잠깐의 조합이 생겼다. 그래서 앱은 상태 리스너가 오면 스위치·상태를 함께 다시 읽는다.
- **Vehicle SDK:** 연결 완료 콜백(`initialize()`는 반환값·콜백 없음)과 와이퍼 스위치 리스너(`registerWiperState`만 있음)가 없다. 앱은 와이퍼·기어·창문을 1초마다 읽는다. 기어 `GEAR_PARK`, 창문 4곳 0(닫힘)을 읽었다.
- **오프라인과 큐:** 서버를 끄면 약 10초 뒤 오프라인이 됐다. 꺼진 동안 바꾼 와이퍼 값은 큐에 1건 남았고, 서버를 다시 켠 뒤 다음 변경 때 함께 보내져 큐가 비었다.
- **NaviHelper는 이번에 확인하지 못했다.** 콜드 부팅 뒤 내비 앱이 "내비게이션 실행이 지연되고 있습니다"에서 멈췄다. 다시 시도와 강제 종료 뒤 재실행도 같았다. 로그에는 `navi not ready`가 남고, 상태는 `appStatus=AuthComplete, engineStatus=NaviEngineComplete, mapStatus=MapInitComplete`였다. 그래서 앱의 TBT·길안내 중 신호와 개포를 지나는 경로의 실제 지하차도 TBT 값은 확인하지 못했다. 같은 호출은 2026-11-11 시험 앱에서 동작했다(위 NaviHelper 줄). 에뮬레이터를 다시 콜드 부팅하자 내비가 정상으로 돌아와 Task 14 때 확인했다(아래).

### 앱 코드로 확인한 음성·차량 준비·우회 (2026-11-12, Task 14)

디버그 확인 화면의 버튼(또는 `--es action warn|offer|inform|prepare|offline|reroute`)으로 `ActionRunner`를 실제 Pleos 구현과 함께 돌렸다.

- **Virtual Console HTTP로 차 상태를 맞춘다.** `PUT /vehicle/body?window_fl=50&window_rr=30`(창문 %, `window_fl/fr/rl/rr`), `PUT /vehicle/hvac?front_defroster=false`, `PUT /vehicle/lights?feature=headlamp_mode&value=Off`. 쓸 수 있는 항목과 값은 `GET /vehicle/catalog`에 있다. "Virtual Console을 닫은 상태"는 하트비트(`POST /browser/session`)를 멈추고 3초 넘게 기다리면 된다(`emulator_active: false`).
- **차량 준비:** 열린 상태에서는 창문·디프로스터·하향등이 모두 다시 읽은 값과 Virtual Console(`windows` 0, `front_defroster_on: true`, `headlamp_mode: LowBeam`)에 반영됐다. 다시 실행하면 모두 `ALREADY`라 말하지 않았다. 닫은 상태에서는 set 성공 콜백만 오고 2초 동안 값이 그대로라 모두 `NOT_APPLIED`가 됐고 "…켜 주세요"로 권했다.
- **TTS:** 경고 문장 하나가 `say` 시작부터 `onDone`까지 약 6.6초, 오프라인 문장은 약 4.6초였다.
- **STT 무응답:** 질문 뒤 8초가 지나 `stop()`을 부르고 1.5초 더 기다린 뒤 null로 끝났다. 대답 인식은 사용자가 말할 수 없는 환경이라 확인하지 못했다. `adb emu avd hostmicon` 뒤 맥 `say -v Yuna "응"`을 스피커로 틀어도 인식되지 않았다.
- **NaviHelper:** 콜드 부팅 뒤 내비가 정상이었다. 시험 앱으로 목적지 (37.4770, 127.0540)을 요청하자 `ROUTE_DRIVE`와 TBT `LEFT 80, LEFT 470, LEFT 1570, ENTER_UNDER_L 8970, LEFT 9550, RIGHT 9830, ARRIVE 9860`(누적 m)이 왔다. 앱의 `PleosVehicleSignals`도 길안내 중 true와 같은 TBT 목록을 받았다.
- **사람이 내비 화면에서 시작한 길안내도 같다(2026-11-12, Task 15 뒤):** Pleos 내비 "어디로 갈까요?"에 화면 키보드로 "개포"를 검색해 "개포래미안포레스트아파트"(9.8 km)를 고르고 "안내 시작"을 누르자, 앱의 `PleosVehicleSignals`가 길안내 중 true와 TBT `LEFT 80, LEFT 390, LEFT 1100, ENTER_UNDER_L 7400, LEFT 580, RIGHT 120, ARRIVE 20`(구간 m)을 받았다. SDK로 요청한 경로와 똑같이 읽힌다.
- **경유지 추가:** 길안내 중에 `addWaypoint(RequestWaypointInfo(lat, lng, "우회 지점", WaypointIndex.FIRST, …))` + `requestReRoute()`를 부르자 내비 다음 목적지가 "우회 지점"으로 바뀌고 경로를 다시 찾았다(32분, 10 km). 앱은 약 6초 뒤 성공 콜백을 받았다. 다시 찾은 경로의 TBT(구간 m)는 `RIGHT 700, RIGHT 50, ENTER_IC_C 110, TOLL 40, EXIT_IC_R 7400, ENTER_UNDER_L 1000, LEFT 1200, WAY 20, UTURN 680, RIGHT 290, RIGHT 260, ARRIVE 30`이다. 누적하면 지하차도 진입 9,300 m, 경유지 10,520 m, 유턴 11,200 m라, 경유지보다 1,220 m 앞에서 어떤 지하차도를 지나고(TBT는 어느 지하차도인지 주지 않는다) 경유지를 지난 뒤 유턴했다.
- **내비 현재 위치가 성남에 고정되는 까닭을 찾아봤지만 바꿀 방법은 찾지 못했다.** `adb emu geo fix`는 Android GPS와 FusedLocation을 바꾸지만(`dumpsys location`), 내비 앱이 Android GPS에 건 요청은 PASSIVE와 30분 간격 HIGH_ACCURACY 하나뿐이고 받은 위치는 1개였다. 내비 엔진 맵매칭 로그는 계속 (37.4127, 127.0947), `sat(0)`이다. geo fix에 위성 수(10)를 붙여도 Android 위치의 `satellites`는 0이고 내비도 그대로였다. Virtual Console `/vehicle/catalog`에는 위치 항목이 없다. 내비 엔진에 경로 모의 주행 코드(`api_route_simul`)가 있지만 NaviHelper SDK(경로 요청에 출발점 없음), 내비 앱이 받는 인텐트 어디에도 켜는 길이 없고, 내비는 시스템 앱이며 에뮬레이터가 production 빌드라 `adb root`가 안 된다. 실제 차에서는 차량 GNSS·센서 쪽 경로로 위치가 들어가고 에뮬레이터는 그것을 흉내 내지 않는 것으로 추정한다(확인 안 됨). 공모전 사무국에 문의한다.
- **공식 문서:** document.pleos.ai의 NaviHelper API 페이지는 자바스크립트로 그려져 `curl`로는 본문이 보이지 않았다. 위 동작은 문서가 아니라 에뮬레이터에서 본 것이다.

### 서비스·알림·데모·라이브 업로드 (2026-11-12, Task 15)

- **알림 권한은 사용자 10에 줘야 한다.** Pleos 에뮬레이터 앱은 사용자 10으로 돈다. `adb shell pm grant ai.wipernet.car android.permission.POST_NOTIFICATIONS`는 사용자 0에만 줘서, 앱 알림이 모두 막혔다(`dumpsys notification`에 `importance=NONE`, `numBlocked`). `pm grant --user 10 …`으로 풀었다. 실제 사용에서는 첫 실행 때 권한 창이 뜬다.
- **Pleos는 앱 알림을 화면에 팝업으로 띄우지 않는다.** 높은 중요도 채널이어도 알림 창(`cmd statusbar expand-notifications`)에만 들어간다. 알림 창의 "예" 버튼은 앱의 수신기로 답을 보낸다. 창이 펼쳐지는 중에 누르면 눌리지 않았다.
- **데모 순서:** 앱 시작 뒤 33초 "곧 비 구간", 143초 "곧 더 강한 비 구간", 213초 개포 2.0 km 경고와 우회 질문. 홈으로 나가 내비를 띄워 둬도 계속 나왔고, 앱 화면은 앞으로 뜨지 않았다.
- **오프라인 안내는 기어 P에서는 나오지 않는다**(A3: 기어 P면 아무것도 안 함). Virtual Console `PUT /vehicle/gear?gear=Drive`로 D에 둔 뒤 서버를 끈 채 라이브로 켜자 안내가 나왔다. 기어 D에서도 앱 화면이 가려지지 않았다(`distractionOptimized`).
- **라이브 업로드:** 서버 시계가 없을 때는 보내지 않았다. 차가 안 움직이는 에뮬레이터에서는 앱을 켠 위치가 길안내 출발지라 300 m 규칙에 걸려, `adb emu geo fix 127.0500 37.4800`으로 옮긴 뒤 보냈다. 와이퍼 Low·High·Off가 서버 시각(2022-08-08 20:00)으로 들어왔고, 꺼진 뒤 길안내 중이라 약 5분 뒤 "꺼짐"을 다시 보냈다.
- **데모를 앱 화면에 둔 채 끝까지:** 213초 위쪽 경고 띠, 우회 질문 무응답이면 420초에 위험 단계로 다시 경고, 565초 도착 뒤 주차(기어 P)되어 "비 지도" 버튼이 나오고 열렸다(약 9.5분).
- **재부팅 자동 시작:** `adb reboot` 뒤 부팅 완료 약 25초 만에 서비스가 저절로 시작됐다. 백그라운드에서 시작돼 microphone 타입이 막히고 specialUse 타입으로 돈다.
- **AUTO 와이퍼:** Task 13에서 Virtual Console을 Auto로 바꿨을 때 상태가 `ON`으로 7초 넘게 그대로였다. 와이퍼 한 번마다 ON/OFF가 바뀌지 않는다면 앱은 15초 뒤 40회(연속)로 보낸다. 더 길게는 보지 않았다.

### 에뮬레이터 설치와 실행에서 알게 된 것

- 시스템 이미지는 Android Studio SDK Manager → SDK Update Sites에 `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-arm64-sys-img.xml`을 추가해 받는다. 이름은 "Pleos Connect v2.1.0 APIs arm64 System Image"(API 34)이고, 받는 크기 4.3 GB, 풀면 약 19 GB다.
- AVD는 문서대로 Automotive → Pleos Connect v2.1.0, Cold boot, RAM 4 GB. 처음 켤 때 데이터 영역(기본 10 GB) + 2 GB의 빈 디스크가 필요하다. 디스크가 부족하면 "Not enough space to create userdata partition"으로 바로 꺼진다. 이 맥은 `~/.android/avd/Pleos_Connect_v2.1.0.avd/config.ini`의 `disk.dataPartition.size`를 6G로 줄였다.
- M1 맥에서 GPU 기본값(`auto`, Vulkan lavapipe)으로 켜면 "detected a hanging thread 'QEMU2 main loop'" 뒤 꺼졌다. `emulator -avd Pleos_Connect_v2.1.0 -no-snapshot-load -gpu host -feature -Vulkan`으로 켜면 부팅한다.
- **Virtual Console은 브라우저 화면이 열려 있어야 차량 상태를 갱신한다.** 1초마다 `POST /browser/session?client=…`을 보내고, 3초 동안 오지 않으면 `emulator_inactive`가 된다. 그동안 앱의 set은 성공 콜백만 오고 반영되지 않는다. 영상 녹화와 확인 때는 Virtual Console 탭을 열어 둔다.
- 창문 위치는 앱에서 0~10, Virtual Console에서 0~100%다(앱 3 = 30%). 처음 값은 0이다.
- Pleos 홈 화면은 부팅 직후 내비 앱을 앞으로 띄운다. 그때 `adb shell am start -n ai.wipernet.car/.MainActivity`는 "task has been brought to the front"만 내고 앱이 보이지 않을 수 있다. `am start --user current -f 0x10008000 -n …`(새 task)로 띄우면 된다.
- 앱 글씨는 이 에뮬레이터(2560×1440)에서 생각보다 작게 보인다. Material 기본 크기(대화상자 등)는 작아서 글자 크기를 직접 키웠다.
- Android Studio 2026.2.2의 내장 JDK는 25다. Gradle 9.9.0 + AGP 9.5.1(compileSdk 37)로 빌드했다.

## 2. 외부 자료

### 기상청 API허브 (https://apihub.kma.go.kr)

키 하나(`KMA_API_HUBKEY`)로 받는다. **API마다 활용신청을 따로 해야 한다.**
- 활용신청하지 않은 API를 부르면 `403 "활용신청이 필요한 API 입니다"`가 온다.
- 키가 틀리면 `401 "유효한 인증키가 아닙니다"`가 온다.

아래 7개를 활용신청했고, 모두 2022-08-08 자료가 나오는 것을 확인했다.

| 메뉴 → 항목 | 경로 (`/api/typ01/...`) | 쓰는 곳 | 2022-08-08 확인 결과 |
|---|---|---|---|
| 방재기상관측(AWS) → 1.1 AWS 매분자료 | `cgi-bin/url/nph-aws2_min` | Task 5 | 21:00 직전 60분 강수: 강남(400) 75.5 mm, 서초(401) 92.0 mm, 기상청(410, 동작구) 136.5 mm. 전체 지점 조회는 한 번에 10분까지. 한 지점 하루치 조회는 응답이 약 1,130줄에서 잘린다(아래 AWS 절) |
| 지상관측 지점정보 → 1.1.2 AWS | `url/stn_inf.php?inf=AWS` | Task 5 | 강남 400 (127.08166, 37.49820), 서초 401 (127.02601, 37.48462) |
| 레이더 강수량(HSR) → 2.2.1 HSR에 마스킹처리 | `cgi-bin/url/nph-rdr_cmp1_api?cmp=HSR` | Task 4b | 2305×2881 격자, 값은 dBZ×100. `disp=B`(이진, 13 MB, 약 5초)로 받는다. 강남 부근 21:00 42~50 dBZ |
| 레이더 강수량(HSR) → 2.2.2 HSR기반 1시간 누적 | `cgi-bin/url/nph-rdr_cmp1_api?cmp=PCPH&acc=60` | 쓰지 않음 | 같은 격자. 값이 AWS 1시간 강수를 따라가지 않는다(아래 레이더 절). 단위가 문서에 없다 |
| 레이더 강수량(HSR) → 4.1 격자데이터 위경도 | `cgi-bin/url/nph-rdr_latlon_api` | Task 4b | 격자점별 위도·경도. 데모 지역 칸 것만 `backend/data/radar_grid_gangnam.csv`에 둔다 |
| 기상특보 → 1.1 특보구역 | `url/wrn_reg.php` | Task 4a | 서울동남권 `L1100100` (2020-05-14부터) |
| 기상특보 → 1.2 특보자료 | `url/wrn_met_data.php` | Task 4a | 서울동남권: 08:00 호우주의보 발효 → 12:50 호우경보 → 08-10 02:30 해제. 기록은 `backend/data/kma_warnings_20220808.json` |

**이용 조건**
- 각 API 페이지와 이용안내에 공공누리 **"공공저작물 자유이용허락 - 출처표시"**(제1유형)가 표시되어 있다.
  - 이용안내: "데이터는 공공누리 적용을 받습니다. 공공누리 유형별 이용조건에 따라 이용 가능합니다."
  - 제1유형은 출처만 표시하면 상업적 이용과 변경이 허용된다.
- API허브 약관(2023-02-06 시행)에는 상업적 이용을 막는 조항이 없다. 다만 아래를 지켜야 한다.
  - 승인받은 애플리케이션에서만 키를 쓴다 (제5조 8항).
  - 이용권을 양도하거나 판매하지 않는다 (제13조).
  - 3개월 동안 쓰지 않으면 서비스가 중단될 수 있다 (제5조 10항).
  - 제공 범위와 이용 횟수는 회원 유형과 목적에 따라 다르다.
- 실제 배포 때 따로 확인할 것은 두 가지다.
  - 호출 횟수 한도와 운영 계정 신청.
  - 기상산업진흥법상 **기상예보업 등록**(제6조)이 필요한지. 법은 "미래의 기상상태를 예상하여 제공"하는 사업에 등록을 요구한다. 와이퍼넷은 지금 내리는 비와 지하차도 위험을 알리고 예보는 하지 않지만, 상용화 전에 기상청에 확인한다.

**알아 둘 것**
- python.org판 Python 3.12의 `urllib`는 인증서 묶음이 없어 API허브 TLS 확인에 실패한다. 서버 코드는 `httpx`(certifi 사용)로 부른다.

### AWS 강수 (Task 5)

- **관측소 고르기:** 데모 지역(위도 37.47~37.53, 경도 127.00~127.07) 안의 AWS는 서초(401) 한 곳뿐이다. 그래서 사방으로 0.08°(약 8 km) 넓힌 사각형 안의 22곳을 받는다(사용자 결정, 2026-11-06).
- **한 지점 하루치 조회는 잘린다.** `tm1=202208080000&tm2=202208082359`로 부르면 18:53에서 끝난다. 반나절씩(00:00~11:59, 12:00~다음 날 00:00) 두 번 부른다.
- **RN-DAY(일 누적)는 00:01에 0으로 돌아간다.** 00:00 줄의 값은 전날 합계다. 다음 날 00:00 줄은 그날 합계다(서초 354.5 mm).
- **2022-08-08 빠진 값**(-99.9)
  - 10:40, 12:30 무렵에는 거의 모든 지점의 값이 1분씩 비어 있다.
  - 오전동(366)은 하루 내내 값이 없어 정규화 CSV에 없다. 그래서 강우장은 21곳으로 만든다.
  - 남현(425)은 20:25에 일 누적이 126.0 → 38.0으로 떨어지고, 20:34부터 값이 없다. 과천(590)은 20:43부터, 성남(572)은 하루의 절반쯤 값이 없다.
- 정규화 결과 서초(401) 20:00~21:00 합계는 92.0 mm로 RN-60m과 같다.

### 레이더 (Task 4b)

**문서로 확인한 것** (API허브 '레이더 강수량(HSR)' 페이지 요청인자 표)
- `obs`는 `ECHO`(dBZ×100)와 `EBH`(고도)뿐이다. **mm/h 값을 바로 주는 항목은 없다.**
- `disp=B`는 nx, ny(2바이트) 다음 nx×ny개 short다. 격자는 왼쪽 아래부터 가로 우선이다.
- -30000은 관측 영역 밖, -25000은 에코 없음이다.
- `tm`이 없으면 가장 최근 장면을 준다.
- `acc`(누적)는 60분의 배수만 된다.

**직접 확인한 것**
- 바이트 순서는 문서에 없다. 응답 앞 4바이트를 little-endian으로 읽어야 격자 수(2305, 2881)가 나온다.
- 격자 위경도 텍스트(위도 67 MB, 경도 74 MB)는 한 번 응답이 잘린 적이 있다. 스크립트는 값 개수가 격자 수와 다르면 멈춘다.

**반사도 → mm/h: Marshall-Palmer(Z = 200 R^1.6), 가정**
- 기상청 문서에 나온 식이 아니다. 기상학에서 표준으로 쓰는 식이라 골랐고, 결과에 맞춰 바꾸지 않았다.
- 2022-08-08 서초 AWS(401)와 그 칸의 레이더를 비교했다. AWS 10분 값은 일 누적 강수(RN-DAY) 차이×6이다.

| 시각 (1시간) | 레이더 (mm) | AWS 서초 (mm) |
|---|---|---|
| 17시 | 9.5 | 14.0 |
| 18시 | 3.2 | 3.5 |
| 19시 | 10.4 | 23.0 |
| 20시 | 39.4 | 92.0 |
| 21시 | 34.7 | 84.5 |
| 22시 | 26.1 | 67.5 |

- 비가 시작하고 그치는 시각은 10분 단위로 맞는다(17:40 시작, 18:40 그침, 19:30 다시 시작).
- **센 비에서는 레이더가 AWS의 절반 아래다.** 20~22시 1시간 강수가 AWS의 약 40%다.
  - 집중호우에서 Marshall-Palmer가 적게 잡는 것은 알려진 한계다.
  - 이날 데모 지역 레이더 값은 10분 구간 기준 최대 62.5 mm/h다. 60 mm/h를 넘는 칸-구간은 7,020개 중 6개뿐이다.
- AWS에 맞춰 식을 고치지 않는다. 시뮬레이터 차는 AWS 강우장으로 와이퍼를 돌리기 때문이다. 레이더를 AWS에 맞추면 검증(와이퍼 추정 vs 레이더)이 자기 자신과 비교하는 구조가 된다(설계 6.5-1).
- 강남 AWS(400)는 경도 127.082로 데모 지역(127.07까지) 밖이라 칸 비교에서 뺐다.

**HSR기반 1시간 누적(PCPH)은 쓰지 않는다**
- `obs=ECHO`로 받은 값을 100으로 나눠 AWS 1시간 강수와 비교했지만 따라가지 않았다. 18:00 강남이 38.7인데 AWS는 2.0 mm다.
- 이 값의 단위는 문서에 없다.

### 공공데이터포털

신청하지 않는다. 기상특보는 API허브에서 2022년 자료까지 받을 수 있다.

### 재난문자

- 공공데이터포털의 `행정안전부_긴급재난문자` API는 갱신 주기가 "실시간"이다.
  - 다만 제공 기간이 **2023년~2025-01**로 표시되어 2022-08-08 문자는 받을 수 없다.
  - 이용허락범위는 **공공누리 제4유형(출처표시, 상업적 이용금지, 변경금지)**이다 (https://www.data.go.kr/data/15134001/openapi.do, 2026-11-05 확인).
- 그래서 지금은 아래처럼 정한다.
  - **데모:** 2022-08-08 문자는 공개 보도로 손 정리한다 (`backend/data/alerts_20220808.json`, 출처 포함).
  - **실시간:** 연동하지 않는다. `LiveOfficialSource.alerts()`는 빈 목록을 돌려준다.
  - **상업 배포:** 제4유형이라 그대로는 쓸 수 없다. 행정안전부(재난정보통신과)와 별도로 협의해야 한다.
