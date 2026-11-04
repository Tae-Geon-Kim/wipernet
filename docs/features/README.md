# 와이퍼넷 기능지도 (Features Map)

> 에이전트는 **구현 전에** 이 문서를 읽고, **구현 후에** 같은 PR에서 고친다 ([CLAUDE.md](../../CLAUDE.md) 5장).
> 이 문서는 지금 상태만 적는다. 바뀐 내역은 git과 PR에 남는다.

- 마지막 갱신: 2026-11-04, 브랜치 `docs/sim-fixes-external-data` (구현 시작 전)
- 근거: [설계](../2026-10-30-wipernet-design.md), [구현 계획](../superpowers/plans/2026-10-30-wipernet.md)

## 상태 표기

| 표시 | 뜻 |
|---|---|
| ✅ | 에뮬레이터(앱) 또는 실제 서버 실행(백엔드)으로 동작을 확인했다 |
| 🧪 | 단위 테스트만 통과했다 |
| 🎬 | 데모 모드(녹화 시나리오 재생)에서만 확인했다. 실제 SDK 신호로는 아직 확인하지 않았다 |
| 📐 | 설계만 있다. 코드가 없다 |

ID(`S1`, `V2`, `A3` …)는 이 문서 안에서 기능을 가리키는 이름이다. 코드에는 쓰지 않는다.

---

## 1. 한눈에 보기

```
[S] 시뮬레이터 ──(같은 API: POST /v1/wiper-events)──▶ [V] 서버 ◀──(조회: rain-map, underpass-risk)── [A] 차량 앱
      │                                                  │                                            ▲
      └── S7 데모 시나리오 내보내기 ── scenario.json ──────┼────────────────────────────────────────────┘ (데모 모드)
                                                         ├── V6 뷰어 (브라우저, 영상 녹화용)
                                                         └── V7·V8 외부 자료 (특보·재난문자·레이더, 실시간 / 기록 재생) → V9 합치기
```

| 영역 | ID | 기능 | 상태 | 계획 Task |
|---|---|---|---|---|
| 시뮬레이터 | S1 | 기상청 AWS 자료 정규화 | 📐 | 5 |
| | S2 | 강우장 (역거리가중, 관측소 70/30 분할) | 📐 | 5 |
| | S3 | 도로망과 차량 이동 | 📐 | 6 |
| | S4 | 운전자 와이퍼 행동 (수동·AUTO·고장 차) | 📐 | 7 |
| | S5 | 업로드 규칙과 차량 무리 실행 (fleet, runner) | 📐 | 7 |
| | S6 | 검증 리포트 | 📐 | 9 |
| | S7 | 데모 시나리오 내보내기 | 📐 | 10 |
| 서버 | V1 | 와이퍼 세기 변환 | 📐 | 1 |
| | V2 | 격자 비 지도 (3대 합의) | 📐 | 2 |
| | V3 | 지하차도 위험 단계 | 📐 | 3 |
| | V4 | HTTP API | 📐 | 4 |
| | V5 | 메모리 저장소 (`store.py`) | 📐 | 4 |
| | V6 | 시뮬레이션 뷰어 | 📐 | 8 |
| | V7 | 공식 알림 (기상특보, 재난문자) | 📐 | 4a |
| | V8 | 레이더 | 📐 | 4b |
| | V9 | 와이퍼와 외부 자료 합치기 | 📐 | 4c |
| 차량 앱 | A1 | 동의·설정 | 📐 | 11 |
| | A2 | 앞쪽 지하차도 찾기 | 📐 | 12 |
| | A3 | 위험 판단 (경고·우회·준비·쿨다운) | 📐 | 12 |
| | A4 | 차량 신호 (Pleos / 재생) | 📐 | 13 |
| | A5 | 업로드 큐와 임시 ID | 📐 | 13 |
| | A6 | 위험 정보 클라이언트 (라이브 / 재생) | 📐 | 13 |
| | A7 | 음성 안내 | 📐 | 14 |
| | A8 | 차량 준비 (창문·김서림·전조등) | 📐 | 14 |
| | A9 | 우회 경로 | 📐 | 14 |
| | A10 | 예/아니오 응답 (음성 + 알림 버튼) | 📐 | 14, 15 |
| | A11 | 동작 실행기 (고정 문구) | 📐 | 14 |
| | A12 | 앱 컨트롤러 (데모 / 라이브 모드) | 📐 | 15 |
| | A13 | 주행 화면 | 📐 | 15 |
| | A14 | 비 지도 화면 (기어 P) | 📐 | 15 |
| | A15 | 뒤에서 도는 서비스 | 📐 | 15 |
| | A16 | 업로드 규칙 (언제 보내나) | 📐 | 15 |
| 공유 계약 | C1 | API 계약 `docs/api.md` | 📐 | 1, 4 |
| | C2 | 지하차도 목록 `shared/underpasses_gangnam.json` | 📐 | 1 |
| | C3 | 데모 시나리오 `scenario.json` | 📐 | 10 |
| | C4 | 와이퍼 스위치·상태 값 | 📐 | 1 |
| | C5 | 업로드 규칙 판정 사례 `shared/upload_policy_cases.json` | 📐 | 7, 15 |
| | C6 | 외부 자료 기록 (2022-08-08 특보·재난문자·레이더) | 📐 | 4a, 4b |
| 제출물 | D1 | README | 초안 (🚧 표시 남음) | 16 |
| | D2 | SDK·에뮬레이터 확인 기록 `docs/setup-status.md` | 📐 | 0, 13, 14 |
| | D3 | 영상 대본, 제출 점검표 | 📐 | 16 |
| | D4 | APK (`release/`, GitHub Releases) | 📐 | 16 |

---

## 2. 의존 관계 (바꾸면 같이 봐야 하는 곳)

| 바꾸는 것 | 영향받는 기능 |
|---|---|
| C4 와이퍼 값 목록·세기표 | V1, S4 `MMPH_TO_SWITCH`, A4 `PleosVehicleSignals`, A16, C3 |
| `auto_wpm` (AUTO 와이퍼 횟수) | V1, V2, C1, S4, S5, A16 `AutoWipeCounter` |
| V2 `CellEstimate` 필드 (`bins`, `saturated_min` 포함) | V3, V9, V4, C1, V6, S6, S7, A6 |
| V9 단계 규칙·`reasons` 값 | V4, C1, V6, S6, S7, C3, A3 `Model.kt`, A11 고정 문구 |
| C5 업로드 규칙 사례 | S5 `should_send`, A16 `UploadPolicy` (둘 다 같은 파일로 테스트) |
| V2 격자 칸 크기·`cell_of` 식 | A16 (앱도 같은 식으로 칸을 계산한다) |
| A4 `VehicleSignals` (`guiding` 포함) | A12, A16, `PleosVehicleSignals`, `ReplayVehicleSignals` |
| V3 `UnderpassRisk` 필드·단계 기준 | V4, C1, V6, S7(경고 시각 탐색), A3, A6, A12 `Model.kt` |
| C1 API 계약 | V4, S5, A5, A6, V6 |
| C2 지하차도 목록 (`gu`, `warning_area` 포함) | V3, V7 `classify_alert`, V9, S3 `route_through`, S7 |
| C3 시나리오 스키마 | S7, A4 `ReplayVehicleSignals`, A6 `ReplayHazardClient`, `Scenario.kt` |
| A1 `Settings` | A3, A8, A12, 설정 화면 |
| A3 `Action` 종류 | A11, A12, A15(경고 알림) |
| A10 `ConsentPrompt` | A11, A15 |
| V5 저장 방식 | V4만. 다른 모듈은 `store.py`를 직접 알지 않는다 |

**흐름 요약**
- 시뮬레이터와 차량 앱은 **원시** 와이퍼 이벤트(`switch`, `state`)만 보낸다. 세기 계산은 서버(V1)에서만 한다.
- 모든 시간 계산은 이벤트 시각 기준이다. 서버 시계는 받은 이벤트 중 가장 늦은 시각이다(`/v1/clock`). 그래서 시뮬레이션을 빠르게 재생할 수 있다.
- 데모 모드 앱은 서버 없이 C3만 읽는다. 라이브 모드 앱은 서버(`http://10.0.2.2:8000`)에 붙는다.
- 라이브 모드 앱은 이벤트 시각으로 기기 시각이 아니라 마지막으로 받은 `/v1/clock` 값을 쓴다. 값이 없으면 보내지 않는다(설계 6.10.1). 그래서 라이브 모드는 시뮬레이터와 함께만 돈다(공모전 범위).
- 외부 자료(V7, V8)는 서버만 받는다. V9가 와이퍼 판단과 합치고, 위험을 올리기만 하고 내리지 않는다(설계 6.10.4). 외부 자료도 실시간 / 기록 재생 두 가지라 2022-08-08 재현에 맞춘다.

---

## 3. 시뮬레이터 (`backend/wipernet_sim`)

| ID | 기능 | 동작 | 코드 위치 | 테스트 |
|---|---|---|---|---|
| S1 | AWS 자료 정규화 | 내려받은 원자료를 `station_id,station_name,lat,lng,ts,mmph` 10분 간격 CSV로 바꾼다. 시간 자료면 6구간에 똑같이 나눈다 | `backend/scripts/normalize_kma.py` → `backend/data/kma_20220808_norm.csv` | — |
| S2 | 강우장 | 학습용 관측소만으로 역거리가중(거듭제곱 2). 관측소 30%는 검증용으로 숨긴다(seed 7) | `rainfield.py` | `test_rainfield.py` |
| S3 | 도로망·이동 | OSM 도로망 위 임의 경로(서로 오갈 수 있는 가장 큰 덩어리만). 속도 = 간선 제한속도(없으면 30) × 0.5. 도착하면 새 경로. `route_through`는 지하차도를 지나는 내 차 경로 | `roads.py`, `backend/scripts/fetch_roads.py` → `backend/data/gangnam_drive.graphml` | `test_roads.py` |
| S4 | 운전자 와이퍼 | 비 세기 → 스위치 단계(0.5 mm/h 미만은 꺼짐). 개인차 ±1단계. 차량 25%는 AUTO(최근 60초 와이퍼 횟수 `auto_wpm`을 보냄), 1%는 비와 상관없이 연속 5단인 고장 차 | `driver.py` | `test_driver.py` |
| S5 | 업로드 규칙·차량 무리 실행 | 앱 A16과 같은 업로드 규칙(C5로 테스트). 차량 40%는 길안내 중이라 꺼져도 5분마다 "꺼짐"을 보냄. 현재 100대 / 미래 1,500대. 임시 ID 15분 회전(차마다 엇갈림). `fleet.py`가 이벤트를 만들고 실행기·검증·데모 내보내기가 같이 쓴다. 실행기는 시작할 때 서버 초기화, `--slow-from`으로 구간별 속도 | `upload_policy.py`, `fleet.py`, `runner.py` (`python -m wipernet_sim.runner`) | `test_upload_policy.py`, `test_fleet.py`, `test_runner.py` |
| S6 | 검증 리포트 | (a) 재현: 모든 칸 기준 적중률, 60 mm/h 이하·초과로 나눈 오차, 커버리지, 고장 차 오경보, ID 회전 중복, 밀도 두 가지 비교 (b) 현실성: 숨긴 관측소 비교 (c) 와이퍼 추정 vs 레이더 (d) 와이퍼만 / 외부만 / 둘 다의 지하차도 경고 시각 | `validate.py` → `docs/validation.md` | `test_validate.py` |
| S7 | 데모 시나리오 | 내 차가 위험도 높은 지하차도를 약 6분 달린다. 17~21시에서 "차량 준비 조건 → 3분쯤 그 지하차도 WARNING 이상(근거에 `vehicles`)" 순서가 나오는 구간을 고른다. 우회 경로(`bypass`)도 내보낸다 | `export_demo.py` → C3 | `test_export_demo.py` |

## 4. 서버 (`backend/wipernet_server`)

| ID | 기능 | 동작 | 코드 위치 | 테스트 |
|---|---|---|---|---|
| V1 | 와이퍼 세기 변환 | 스위치 → 0~1 세기(고정표), AUTO는 이벤트의 `auto_wpm`으로 추정(최대 0.9, 미검증 가정), 세기 → mm/h 보간(연속 5단 = 60 mm/h가 상한). MIST·SERVICE·OTHER는 무시 | `wiper.py` | `test_wiper.py` |
| V2 | 격자 비 지도 | 약 500 m 칸. 최근 10분 서로 다른 차 **3대 이상**일 때만 값을 낸다. 차별 최신 세기의 **중앙값**. 1시간 누적은 10분 구간 6개 합. 구간의 차 절반 이상이 수동 연속 5단이면 포화(`saturated_min`). 원시 이벤트는 2시간 뒤 삭제 | `grid.py`, `rainmap.py` | `test_rainmap.py` |
| V3 | 지하차도 위험 단계 | 반경 1 km 칸의 최대 세기·누적으로 NONE / CAUTION / WARNING / DANGER. 위험도 높은 곳은 기준 × 0.75. 기준표는 `level_from`으로 V9도 쓴다 | `underpass.py` | `test_underpass.py` |
| V4 | HTTP API | C1 계약 그대로. 5분 넘게 미래인 이벤트 거부. 배치마다 오래된 이벤트 정리. 뷰어용 차량 조회는 최근 5분 안에 보낸 차만 | `api.py` (`uvicorn wipernet_server.api:app --port 8000`) | `test_api.py` |
| V5 | 메모리 저장소 | 서버 상태를 메모리에만 둔다. DB·Alembic은 공모전 범위 밖(설계 6.9). 저장 방식을 바꿀 때는 이 파일만 바꾼다 | `store.py` | `test_api.py`로 간접 |
| V6 | 뷰어 | `/viewer`. Leaflet 1.9.4 지도, 1초마다 차량 점·비 칸·지하차도 마커(근거 포함) 갱신, 위쪽에 특보·재난문자 한 줄. 영상 녹화용 | `viewer/index.html` | `test_api.py::test_viewer_served` |
| V7 | 공식 알림 | 호우특보(공공데이터포털 기상특보 조회서비스)와 재난문자(재난안전데이터공유플랫폼). 실시간 / 기록 재생. 재난문자는 본문의 지하차도 이름 + 통제·침수면 `closure`, 그 구 또는 시 전체의 호우 안내면 `rain` | `external/official.py`, `backend/scripts/normalize_kma_warnings.py` | `test_official.py` |
| V8 | 레이더 | 기상청 레이더 강우강도를 와이퍼넷 칸별 mm/h로 바꾼다. 실시간 / 기록 재생. 시뮬레이터 입력으로는 쓰지 않는다 | `external/radar.py`, `backend/scripts/fetch_radar.py` | `test_radar.py` |
| V9 | 합치기 | 출처별 단계의 최댓값(위험은 올리기만). 와이퍼 포화 구간은 레이더 값이 더 크면 대체. 레이더만 최대 WARNING, 특보 최대 CAUTION, 재난문자 통제 DANGER·호우 안내 CAUTION. 근거 `reasons`를 함께 낸다. `WIPERNET_EXTERNAL=replay\|live\|off` | `fusion.py` | `test_fusion.py` |

## 5. 차량 앱 (`car-app/`, 패키지 `ai.wipernet.car`)

경로는 `car-app/app/src/main/java/ai/wipernet/car/` 기준.

| ID | 기능 | 동작 | 코드 위치 | 테스트 | 에뮬레이터 확인 |
|---|---|---|---|---|---|
| A1 | 동의·설정 | 첫 실행 동의 화면("동의" 버튼으로만). 기본 데모 모드. 차량 준비 항목별 켜기/끄기. 철회하면 전송 중지·큐 삭제 | `settings/Settings.kt`, `ui/ConsentScreen.kt`, `ui/SettingsScreen.kt`, `MainActivity.kt` | `SettingsTest` | — |
| A2 | 앞쪽 지하차도 찾기 | 내비 TBT가 지하차도 진입이면 그 거리 앞 300 m 안의 지하차도. 아니면 2 km 안, 진행 방향 ±30° | `core/UpcomingUnderpassDetector.kt` | `UpcomingUnderpassDetectorTest` | — |
| A3 | 위험 판단 | 기어 P면 아무것도 안 함. WARNING 이상 → 경고 + 우회 제안, CAUTION → 안내. 같은 지하차도 5분 쿨다운(단계가 오르면 바로). 거절하면 단계가 오를 때만 다시. 앞 1~3분 거리에 10 mm/h 이상 칸 → 차량 준비(10분에 한 번) | `core/Model.kt`, `core/HazardDecider.kt` | `HazardDeciderTest` | — |
| A4 | 차량 신호 | 와이퍼(Vehicle SDK, capability 확인), 위치(FusedLocation, 안 되면 NaviHelper), 기어, TBT(NaviHelper), 창문, 길안내 중인지(NaviHelper, 없으면 최근 30초 TBT). 데모 모드는 C3를 시간대로 재생 | `data/VehicleSignals.kt`, `data/PleosVehicleSignals.kt`, `data/ReplayVehicleSignals.kt` | `ScenarioTest`(재생만) | — |
| A5 | 업로드 큐·임시 ID | 파일 큐, 최대 5,000건(넘으면 가장 오래된 것부터 버림), 한 번에 200건, 실패하면 남기고 순서 유지. 임시 ID 15분 회전. 동의 없으면 안 보냄 | `data/UploadQueue.kt`, `data/TempId.kt` | `UploadQueueTest`, `TempIdTest` | — |
| A6 | 위험 정보 클라이언트 | 라이브: 5초마다 조회(서버 시계 포함), 2번 연속 실패면 오프라인, 내 차 3 km 칸만 보관. 데모: C3 재생 | `data/HazardClient.kt`, `data/LiveHazardClient.kt`, `data/ReplayHazardClient.kt`, `data/Scenario.kt` | `HazardClientTest` | — |
| A7 | 음성 안내 | Pleos TTS(`ON_DEVICE`) | `act/Announcer.kt` | `ActionRunnerTest`(가짜) | — |
| A8 | 차량 준비 | 창문 닫기, 에어컨·디프로스터, 전조등. 기능이 없거나 실패하면 음성 제안으로 바꾼다 | `act/VehiclePrep.kt` | `ActionRunnerTest`(가짜) | — |
| A9 | 우회 경로 | 라이브: NaviHelper 경유지 추가 + 재탐색(경유지 최대 2개). 데모: C3의 우회 경로로 갈아탐 | `act/Rerouter.kt` | `ActionRunnerTest`(가짜) | — |
| A10 | 예/아니오 응답 | 음성(STT "네/아니오")과 시스템 알림의 예/아니오 버튼 중 먼저 온 답. 8초 무응답이면 응답 없음. 앱 화면에는 버튼을 띄우지 않는다 | `act/ConsentPrompt.kt`, `act/NotificationPrompt.kt` | `ActionRunnerTest`(가짜), `EitherPromptTest` | — |
| A11 | 동작 실행기 | A3의 동작을 계획 Task 14의 **고정 문구**로 실행한다. 첫 문장은 근거로 고른다(재난문자 통제 > 주변 차량 > 레이더), 호우특보는 한 문장 덧붙임 | `act/ActionRunner.kt` | `ActionRunnerTest` | — |
| A12 | 앱 컨트롤러 | A15 서비스 안에서 돈다. 모드에 따라 신호·위험 정보 구현을 고른다. 1초마다 찾기 → 판단 → 실행. 라이브 모드는 A16이 고른 이벤트만 큐에 넣고 10초마다 전송(이벤트 시각 = 서버 시계, 없으면 안 넣음). 데모 모드는 업로드 안 함 | `AppController.kt` | — | — |
| A13 | 주행 화면 | 평소에는 작은 상태 표시만. 경고가 있을 때만 위쪽 큰 띠(이름, 거리, 단계 색) | `ui/DrivingScreen.kt` | — | — |
| A14 | 비 지도 화면 | 기어 P에서만. 내 위치 중심 3 km의 비 칸, 지하차도 마커, 진행 방향 | `ui/RainMapScreen.kt` | — | — |
| A15 | 뒤에서 도는 서비스 | 포그라운드 서비스(상시 알림). 동의 후 첫 실행에 시작, 화면을 닫아도 계속, 철회하면 멈춤. 부팅 때 자동 시작 시도. 경고는 음성 + 시스템 알림이고 앱 화면을 앞으로 띄우지 않는다 | `service/WiperNetService.kt`, `service/BootReceiver.kt` | — | — |
| A16 | 업로드 규칙 | 와이퍼 켜짐: 바뀌면 + 60초마다. 켜짐→꺼짐: 한 번. 꺼짐 + 길안내 중: 칸이 바뀔 때 + 300초마다. 꺼짐 + 길안내 아님: 안 보냄. AUTO는 최근 60초 와이퍼 횟수(`autoWpm`)를 세서 보내고, 와이퍼 한 번마다 보내지 않는다. S5와 같은 사례(C5)로 테스트 | `data/UploadPolicy.kt`, `data/AutoWipeCounter.kt` | `UploadPolicyTest`, `AutoWipeCounterTest` | — |

## 6. 공유 계약

| ID | 무엇 | 위치 | 만드는 곳 | 쓰는 곳 |
|---|---|---|---|---|
| C1 | API 계약 (엔드포인트, 요청·응답 모양. 이벤트 `auto_wpm`, 위험 응답 `reasons`·`alerts`, `/v1/external`) | `docs/api.md` | V4, V9 | S5, A5, A6, V6 |
| C2 | 강남 지하차도 목록 (`id, name, lat, lng, base_risk, bypass_waypoint, gu, warning_area`) | `shared/underpasses_gangnam.json`, 만드는 스크립트 `backend/scripts/fetch_underpasses.py` | 손 정리 + OSM | V3, V7, V9, S3, S7 |
| C3 | 데모 시나리오 (`start, step_s, my_car[], bypass, hazards[]`) | `car-app/app/src/main/assets/demo/scenario.json` | S7 | A4, A6 |
| C4 | 와이퍼 값: 스위치 `OFF, AUTO, MIST, INTERMITTENT_LEVEL_1~5, CONTINUOUS_LEVEL_1~5, SERVICE, OTHER` / 상태 `ON, OFF, SERVICE, OTHER` | V1 `wiper.py`가 기준 | Pleos Vehicle SDK | V1, S4, A4 |
| C5 | 업로드 규칙 판정 사례 | `shared/upload_policy_cases.json` | S5 (Task 7) | S5, A16 테스트 |
| C6 | 2022-08-08 외부 자료 기록 (특보, 재난문자 손 정리·출처 포함, 레이더 칸별 값) | `backend/data/kma_warnings_20220808.json`, `backend/data/alerts_20220808.json`, `backend/data/radar_20220808_cells.csv` | V7, V8 스크립트 | V7, V8 기록 재생, S6, S7 |

## 7. 핵심 상수 (바꾸면 테스트와 기획서 숫자가 같이 바뀐다)

| 값 | 어디 |
|---|---|
| 격자 칸 위도 0.0045° × 경도 0.0057° (약 500 m) | V2 `grid.py` |
| 칸 값을 내는 최소 차량 수 3대, 집계 창 10분 | V2 |
| 원시 이벤트 보관 2시간, 임시 ID 15분 | V2, S5, A5 |
| 지하차도 반경 1 km, 단계 기준 20/40/60 mm/h · 30/50/80 mm, 위험도 높음 × 0.75 | V3 |
| 차량 밀도 현재 100대 / 미래 1,500대, AUTO 25%, 길안내 중 40%, 고장 차 1% (가정치) | S4, S5 |
| 운전자 와이퍼 꺼짐 기준 0.5 mm/h 미만 | S4 |
| 최고 단계 = 수동 연속 5단(세기 1.0, AUTO는 제외), 칸의 차 절반 이상이면 포화, 30분 넘으면 근거 `saturated` | V1, V2, V9 |
| 레이더만 최대 WARNING, 특보·재난문자 호우 안내 최대 CAUTION, 재난문자 통제 DANGER, 재난문자 유효 2시간(가정치) | V9 |
| 뷰어 차량 표시 최근 5분 | V4 |
| 경고 쿨다운 300초, 차량 준비 600초에 한 번 | A3 |
| 업로드 큐 5,000건, 한 번에 200건 | A5 |
| 켜짐 재전송 60초, 길안내 중 꺼짐 보고 300초 | A16 |
| 데모 지역 위도 37.47~37.53, 경도 127.00~127.07 / 2022-08-08 17:00~23:00 KST | 전체 |

---

## 8. 위험과 아직 모르는 것

**에뮬레이터에서 확인해야 하는 것** (설계 6.8-2. 확인하면 결과를 D2와 이 표에 적는다)

| 질문 | 영향 | 결과 |
|---|---|---|
| 안드로이드 에뮬레이터 위치 기능이 FusedLocation에 반영되는가 | A4, 라이브 시연 | 미확인 |
| NaviHelper `onTBTInfo`, `onCurrentLocationInfo`가 실제로 오는가 | A2, A4 | 미확인 |
| 차가 켜질 때 앱이 뒤에서 자동 시작되는가 | 뒤에서 도는 앱 모델(설계 5.6) | 미확인 |
| Virtual Console 와이퍼 Low/High가 SDK에서 어떤 값으로 오는가 | C4, V1 | 미확인 |
| 시스템 알림의 예/아니오 버튼이 동작하는가 | A10 | 미확인 |
| Window·HVAC·Light 제어가 Virtual Console에 반영되는가 | A8 | 미확인 |
| NaviHelper에 길안내 중인지 알려 주는 API가 있는가 | A4, A16 | 미확인 |
| 포그라운드 서비스가 Pleos에서 화면 밖에서도 계속 도는가 | A15 | 미확인 |
| AUTO 와이퍼에서 상태(ON/OFF)가 와이퍼 한 번마다 바뀌는가 | A16 `AutoWipeCounter`, V1 | 미확인 |

**외부 자료에서 확인해야 하는 것** (키를 받은 뒤 확인하고 D2에 적는다)

| 질문 | 영향 | 결과 |
|---|---|---|
| 기상특보 API·포털에서 2022-08-08 호우특보가 실제로 나오는가 | V7 기록 재생 | 미확인 (조회 파라미터는 있음) |
| 재난문자 API로 2022년 문자를 받을 수 있는가 | V7 기록 재생 | 어려울 가능성 큼 (제공 기간 2023~2025-01로 표시). 공개 보도로 손 정리 |
| 레이더 합성 자료의 파일 형식·좌표계와 API허브 호출 방식 | V8 | 미확인 (보유 기간 2016~현재는 확인) |

**계획끼리 맞지 않는 것**
- 지금은 없다. 시뮬레이터와 앱의 업로드 규칙 차이는 같은 규칙(C5)을 쓰는 것으로 정리했다(설계 6.10.1).

**그 밖의 위험**
- 기상청 AWS 분 자료가 없으면 시간 자료를 10분 단위로 똑같이 나눠 쓴다(S1). 비 퍼짐이 덜 자연스러워진다.
- AUTO 세기 추정식(V1, S4)은 검증하지 않은 가정이다.
- 와이퍼는 60 mm/h를 넘는 비를 구분하지 못한다(V1 상한). 레이더(V8)가 없으면 1시간 누적은 최대 60 mm라 누적 기준 "위험"(80 mm)에 닿지 않는다. 세기 기준으로는 위험 단계가 나온다(설계 6.10.2).
- 임시 ID 회전 때문에 천천히 움직이는 차가 10분 동안 두 대로 세질 수 있다. 익명성 때문에 구조는 두고 S6에서 비율을 잰다.
- 외부 자료 Task 3개가 늘어 일정이 빠듯하다. 레이더(V8)가 가장 무겁다.
- 서버는 메모리 저장이라 재시작하면 상태가 사라진다(V5). 공모전 시연에는 문제없다.
