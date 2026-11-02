# 와이퍼넷 기능지도 (Features Map)

> 에이전트는 **구현 전에** 이 문서를 읽고, **구현 후에** 같은 PR에서 고친다 ([CLAUDE.md](../CLAUDE.md) 5장).
> 이 문서는 지금 상태만 적는다. 바뀐 내역은 git과 PR에 남는다.

- 마지막 갱신: 2026-11-02, 브랜치 `docs/project-setup` (구현 시작 전)
- 근거: [설계](2026-10-30-wipernet-design.md), [구현 계획](superpowers/plans/2026-10-30-wipernet.md)

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
                                                         └── V6 뷰어 (브라우저, 영상 녹화용)
```

| 영역 | ID | 기능 | 상태 | 계획 Task |
|---|---|---|---|---|
| 시뮬레이터 | S1 | 기상청 AWS 자료 정규화 | 📐 | 5 |
| | S2 | 강우장 (역거리가중, 관측소 70/30 분할) | 📐 | 5 |
| | S3 | 도로망과 차량 이동 | 📐 | 6 |
| | S4 | 운전자 와이퍼 행동 (수동·AUTO) | 📐 | 7 |
| | S5 | 차량 무리 실행 (runner) | 📐 | 7 |
| | S6 | 검증 리포트 | 📐 | 9 |
| | S7 | 데모 시나리오 내보내기 | 📐 | 10 |
| 서버 | V1 | 와이퍼 세기 변환 | 📐 | 1 |
| | V2 | 격자 비 지도 (3대 합의) | 📐 | 2 |
| | V3 | 지하차도 위험 단계 | 📐 | 3 |
| | V4 | HTTP API | 📐 | 4 |
| | V5 | 메모리 저장소 (`store.py`) | 📐 | 4 |
| | V6 | 시뮬레이션 뷰어 | 📐 | 8 |
| 차량 앱 | A1 | 동의·설정 | 📐 | 11 |
| | A2 | 앞쪽 지하차도 찾기 | 📐 | 12 |
| | A3 | 위험 판단 (경고·우회·준비·쿨다운) | 📐 | 12 |
| | A4 | 차량 신호 (Pleos / 재생) | 📐 | 13 |
| | A5 | 업로드 큐와 임시 ID | 📐 | 13 |
| | A6 | 위험 정보 클라이언트 (라이브 / 재생) | 📐 | 13 |
| | A7 | 음성 안내 | 📐 | 14 |
| | A8 | 차량 준비 (창문·김서림·전조등) | 📐 | 14 |
| | A9 | 우회 경로 | 📐 | 14 |
| | A10 | 예/아니오 응답 | 📐 | 14 |
| | A11 | 동작 실행기 (고정 문구) | 📐 | 14 |
| | A12 | 앱 컨트롤러 (데모 / 라이브 모드) | 📐 | 15 |
| | A13 | 주행 화면 | 📐 | 15 |
| | A14 | 비 지도 화면 (기어 P) | 📐 | 15 |
| 공유 계약 | C1 | API 계약 `docs/api.md` | 📐 | 1, 4 |
| | C2 | 지하차도 목록 `shared/underpasses_gangnam.json` | 📐 | 1 |
| | C3 | 데모 시나리오 `scenario.json` | 📐 | 10 |
| | C4 | 와이퍼 스위치·상태 값 | 📐 | 1 |
| 제출물 | D1 | README | 초안 (🚧 표시 남음) | 16 |
| | D2 | SDK·에뮬레이터 확인 기록 `docs/setup-status.md` | 📐 | 0, 13, 14 |
| | D3 | 영상 대본, 제출 점검표 | 📐 | 16 |
| | D4 | APK (`release/`, GitHub Releases) | 📐 | 16 |

---

## 2. 의존 관계 (바꾸면 같이 봐야 하는 곳)

| 바꾸는 것 | 영향받는 기능 |
|---|---|
| C4 와이퍼 값 목록·세기표 | V1, S4 `MMPH_TO_SWITCH`, A4 `PleosVehicleSignals`, C3 |
| V2 `CellEstimate` 필드 | V3, V4, C1, V6, S6, S7, A6 |
| V3 `UnderpassRisk` 필드·단계 기준 | V4, C1, V6, S7(경고 시각 탐색), A3, A6, A12 `Model.kt` |
| C1 API 계약 | V4, S5, A5, A6, V6 |
| C2 지하차도 목록 | V3, S3 `route_through`, S7 |
| C3 시나리오 스키마 | S7, A4 `ReplayVehicleSignals`, A6 `ReplayHazardClient`, `Scenario.kt` |
| A1 `Settings` | A3, A8, A12, 설정 화면 |
| A3 `Action` 종류 | A11, A12 |
| V5 저장 방식 | V4만. 다른 모듈은 `store.py`를 직접 알지 않는다 |

**흐름 요약**
- 시뮬레이터와 차량 앱은 **원시** 와이퍼 이벤트(`switch`, `state`)만 보낸다. 세기 계산은 서버(V1)에서만 한다.
- 모든 시간 계산은 이벤트 시각 기준이다. 서버 시계는 받은 이벤트 중 가장 늦은 시각이다(`/v1/clock`). 그래서 시뮬레이션을 빠르게 재생할 수 있다.
- 데모 모드 앱은 서버 없이 C3만 읽는다. 라이브 모드 앱은 서버(`http://10.0.2.2:8000`)에 붙는다.

---

## 3. 시뮬레이터 (`backend/wipernet_sim`)

| ID | 기능 | 동작 | 코드 위치 | 테스트 |
|---|---|---|---|---|
| S1 | AWS 자료 정규화 | 내려받은 원자료를 `station_id,station_name,lat,lng,ts,mmph` 10분 간격 CSV로 바꾼다. 시간 자료면 6구간에 똑같이 나눈다 | `backend/scripts/normalize_kma.py` → `backend/data/kma_20220808_norm.csv` | — |
| S2 | 강우장 | 학습용 관측소만으로 역거리가중(거듭제곱 2). 관측소 30%는 검증용으로 숨긴다(seed 7) | `rainfield.py` | `test_rainfield.py` |
| S3 | 도로망·이동 | OSM 도로망 위 임의 경로. 속도 = 간선 제한속도(없으면 30) × 0.5. 도착하면 새 경로. `route_through`는 지하차도를 지나는 내 차 경로 | `roads.py`, `backend/scripts/fetch_roads.py` → `backend/data/gangnam_drive.graphml` | `test_roads.py` |
| S4 | 운전자 와이퍼 | 비 세기 → 스위치 단계. 개인차 ±1단계. 차량 25%는 AUTO(켜짐/꺼짐 반복) | `driver.py` | `test_driver.py` |
| S5 | 차량 무리 실행 | 현재 100대 / 미래 1,500대. 10초마다 이벤트를 모아 POST. 켜진 차는 60초마다 heartbeat. 임시 ID 15분 회전 | `runner.py` (`python -m wipernet_sim.runner`) | `test_runner.py` |
| S6 | 검증 리포트 | (a) 재현: 칸별 서버 추정 vs 강우장, 밀도 두 가지 비교 (b) 현실성: 숨긴 관측소 비교 | `validate.py` → `docs/validation.md` | `test_validate.py` |
| S7 | 데모 시나리오 | 내 차가 위험도 높은 지하차도를 약 6분 달리고, 3분쯤 그 지하차도가 WARNING 이상이 되는 구간을 고른다 | `export_demo.py` → C3 | `test_export_demo.py` |

## 4. 서버 (`backend/wipernet_server`)

| ID | 기능 | 동작 | 코드 위치 | 테스트 |
|---|---|---|---|---|
| V1 | 와이퍼 세기 변환 | 스위치 → 0~1 세기(고정표), AUTO는 최근 60초 켜짐 횟수로 추정(미검증 가정), 세기 → mm/h 보간. MIST·SERVICE·OTHER는 무시 | `wiper.py` | `test_wiper.py` |
| V2 | 격자 비 지도 | 약 500 m 칸. 최근 10분 서로 다른 차 **3대 이상**일 때만 값을 낸다. 차별 최신 세기의 **중앙값**. 1시간 누적은 10분 구간 6개 합. 원시 이벤트는 2시간 뒤 삭제 | `grid.py`, `rainmap.py` | `test_rainmap.py` |
| V3 | 지하차도 위험 단계 | 반경 1 km 칸의 최대 세기·누적으로 NONE / CAUTION / WARNING / DANGER. 위험도 높은 곳은 기준 × 0.75 | `underpass.py` | `test_underpass.py` |
| V4 | HTTP API | C1 계약 그대로. 5분 넘게 미래인 이벤트 거부. 배치마다 오래된 이벤트 정리 | `api.py` (`uvicorn wipernet_server.api:app --port 8000`) | `test_api.py` |
| V5 | 메모리 저장소 | 서버 상태를 메모리에만 둔다. DB·Alembic은 공모전 범위 밖(설계 6.9). 저장 방식을 바꿀 때는 이 파일만 바꾼다 | `store.py` | `test_api.py`로 간접 |
| V6 | 뷰어 | `/viewer`. Leaflet 1.9.4 지도, 1초마다 차량 점·비 칸·지하차도 마커 갱신. 영상 녹화용 | `viewer/index.html` | `test_api.py::test_viewer_served` |

## 5. 차량 앱 (`car-app/`, 패키지 `ai.wipernet.car`)

경로는 `car-app/app/src/main/java/ai/wipernet/car/` 기준.

| ID | 기능 | 동작 | 코드 위치 | 테스트 | 에뮬레이터 확인 |
|---|---|---|---|---|---|
| A1 | 동의·설정 | 첫 실행 동의 화면("동의" 버튼으로만). 기본 데모 모드. 차량 준비 항목별 켜기/끄기. 철회하면 전송 중지·큐 삭제 | `settings/Settings.kt`, `ui/ConsentScreen.kt`, `ui/SettingsScreen.kt`, `MainActivity.kt` | `SettingsTest` | — |
| A2 | 앞쪽 지하차도 찾기 | 내비 TBT가 지하차도 진입이면 그 거리 앞 300 m 안의 지하차도. 아니면 2 km 안, 진행 방향 ±30° | `core/UpcomingUnderpassDetector.kt` | `UpcomingUnderpassDetectorTest` | — |
| A3 | 위험 판단 | 기어 P면 아무것도 안 함. WARNING 이상 → 경고 + 우회 제안, CAUTION → 안내. 같은 지하차도 5분 쿨다운. 거절하면 단계가 오를 때만 다시. 앞 1~3분 거리에 10 mm/h 이상 칸 → 차량 준비(10분에 한 번) | `core/Model.kt`, `core/HazardDecider.kt` | `HazardDeciderTest` | — |
| A4 | 차량 신호 | 와이퍼(Vehicle SDK, capability 확인), 위치(FusedLocation, 안 되면 NaviHelper), 기어, TBT(NaviHelper), 창문. 데모 모드는 C3를 시간대로 재생 | `data/VehicleSignals.kt`, `data/PleosVehicleSignals.kt`, `data/ReplayVehicleSignals.kt` | `ScenarioTest`(재생만) | — |
| A5 | 업로드 큐·임시 ID | 파일 큐, 최대 5,000건(넘으면 가장 오래된 것부터 버림), 한 번에 200건, 실패하면 남기고 순서 유지. 임시 ID 15분 회전. 동의 없으면 안 보냄 | `data/UploadQueue.kt`, `data/TempId.kt` | `UploadQueueTest`, `TempIdTest` | — |
| A6 | 위험 정보 클라이언트 | 라이브: 5초마다 조회, 2번 연속 실패면 오프라인, 내 차 3 km 칸만 보관. 데모: C3 재생 | `data/HazardClient.kt`, `data/LiveHazardClient.kt`, `data/ReplayHazardClient.kt`, `data/Scenario.kt` | `HazardClientTest` | — |
| A7 | 음성 안내 | Pleos TTS(`ON_DEVICE`) | `act/Announcer.kt` | `ActionRunnerTest`(가짜) | — |
| A8 | 차량 준비 | 창문 닫기, 에어컨·디프로스터, 전조등. 기능이 없거나 실패하면 음성 제안으로 바꾼다 | `act/VehiclePrep.kt` | `ActionRunnerTest`(가짜) | — |
| A9 | 우회 경로 | NaviHelper 경유지 추가 + 재탐색(경유지 최대 2개) | `act/Rerouter.kt` | `ActionRunnerTest`(가짜) | — |
| A10 | 예/아니오 응답 | STT("네/아니오"), 안 되면 큰 버튼 2개. 8초 무응답이면 응답 없음 | `act/ConsentPrompt.kt` | `ActionRunnerTest`(가짜) | — |
| A11 | 동작 실행기 | A3의 동작을 계획 Task 14의 **고정 문구**로 실행한다 | `act/ActionRunner.kt` | `ActionRunnerTest` | — |
| A12 | 앱 컨트롤러 | 모드에 따라 신호·위험 정보 구현을 고른다. 1초마다 찾기 → 판단 → 실행. 라이브 모드는 이벤트를 큐에 넣고 10초마다 전송 | `AppController.kt` | — | — |
| A13 | 주행 화면 | 평소에는 작은 상태 표시만. 경고가 있을 때만 위쪽 큰 띠(이름, 거리, 단계 색) | `ui/DrivingScreen.kt` | — | — |
| A14 | 비 지도 화면 | 기어 P에서만. 내 위치 중심 3 km의 비 칸, 지하차도 마커, 진행 방향 | `ui/RainMapScreen.kt` | — | — |

## 6. 공유 계약

| ID | 무엇 | 위치 | 만드는 곳 | 쓰는 곳 |
|---|---|---|---|---|
| C1 | API 계약 (엔드포인트, 요청·응답 모양) | `docs/api.md` | V4 | S5, A5, A6, V6 |
| C2 | 강남 지하차도 목록 (`id, name, lat, lng, base_risk, bypass_waypoint`) | `shared/underpasses_gangnam.json`, 만드는 스크립트 `backend/scripts/fetch_underpasses.py` | 손 정리 + OSM | V3, S3, S7 |
| C3 | 데모 시나리오 (`start, step_s, my_car[], hazards[]`) | `car-app/app/src/main/assets/demo/scenario.json` | S7 | A4, A6 |
| C4 | 와이퍼 값: 스위치 `OFF, AUTO, MIST, INTERMITTENT_LEVEL_1~5, CONTINUOUS_LEVEL_1~5, SERVICE, OTHER` / 상태 `ON, OFF, SERVICE, OTHER` | V1 `wiper.py`가 기준 | Pleos Vehicle SDK | V1, S4, A4 |

## 7. 핵심 상수 (바꾸면 테스트와 기획서 숫자가 같이 바뀐다)

| 값 | 어디 |
|---|---|
| 격자 칸 위도 0.0045° × 경도 0.0057° (약 500 m) | V2 `grid.py` |
| 칸 값을 내는 최소 차량 수 3대, 집계 창 10분 | V2 |
| 원시 이벤트 보관 2시간, 임시 ID 15분 | V2, S5, A5 |
| 지하차도 반경 1 km, 단계 기준 20/40/60 mm/h · 30/50/80 mm, 위험도 높음 × 0.75 | V3 |
| 차량 밀도 현재 100대 / 미래 1,500대, AUTO 비율 25% (가정치) | S5 |
| 경고 쿨다운 300초, 차량 준비 600초에 한 번 | A3 |
| 업로드 큐 5,000건, 한 번에 200건 | A5 |
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

**설계에는 있는데 계획에 Task가 없는 것** (구현 전에 사용자와 정한다)
- 뒤에서 도는 동작(포그라운드 서비스, 설계 5.6·6.8-3). 계획 Task 15의 `AppController`는 화면에 붙어 있다.
- 시스템 알림으로 경고하고 예/아니오를 받는 것(설계 5.6-3, 6.8-4). 계획 Task 14는 화면 버튼이다.
- 길안내 중 와이퍼가 꺼진 차도 칸이 바뀔 때와 5분마다 "꺼짐"을 보내는 것(설계 5.1). 계획 Task 15는 "와이퍼 변경 + 켜져 있으면 60초마다"만 있다.

**그 밖의 위험**
- 기상청 AWS 분 자료가 없으면 시간 자료를 10분 단위로 똑같이 나눠 쓴다(S1). 비 퍼짐이 덜 자연스러워진다.
- AUTO 세기 추정식(V1, S4)은 검증하지 않은 가정이다.
- 서버는 메모리 저장이라 재시작하면 상태가 사라진다(V5). 공모전 시연에는 문제없다.
