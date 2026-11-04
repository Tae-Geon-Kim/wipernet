# 와이퍼넷 서버 API 계약

시뮬레이터(`wipernet_sim`), 차량 앱(`car-app`), 뷰어가 이 문서를 기준으로 서버(`wipernet_server`)와 통신한다.
구현은 계획 Task 4(기본 엔드포인트)와 Task 4c(외부 자료 합치기)에서 한다. 구현과 이 문서가 다르면 같은 PR에서 이 문서를 고친다.

- 실행: `cd backend && .venv/bin/uvicorn wipernet_server.api:app --port 8000`
- 에뮬레이터에서 보는 주소: `http://10.0.2.2:8000`
- 본문은 모두 JSON(UTF-8)이다.
- 시각은 ISO 8601에 시간대를 붙여 쓴다. 예: `2022-08-08T21:30:00+09:00`
- **서버 시계**는 지금까지 받은 이벤트의 가장 늦은 `ts`다. 모든 시간 계산은 서버 시계(또는 요청의 `at`) 기준이다. 기기 시각은 쓰지 않는다.

## 엔드포인트

| 메서드·경로 | 하는 일 | 쓰는 곳 |
|---|---|---|
| `POST /v1/wiper-events` | 와이퍼 이벤트를 받는다 | 시뮬레이터, 앱(라이브) |
| `GET /v1/clock` | 서버 시계 | 앱(라이브), 뷰어 |
| `GET /v1/rain-map?at=` | 격자 비 지도 | 앱(라이브), 뷰어, 검증 |
| `GET /v1/underpass-risk?at=` | 지하차도 위험 단계 | 앱(라이브), 뷰어, 검증, 데모 내보내기 |
| `GET /v1/external?at=` | 발효 중 특보와 최근 재난문자 (Task 4c) | 뷰어 |
| `GET /v1/debug/vehicles` | 최근 5분 안에 보낸 차의 마지막 상태 | 뷰어 |
| `POST /v1/reset` | 서버 상태 초기화 | 시뮬레이터(시작할 때) |
| `GET /viewer` | 시뮬레이션 뷰어 페이지 (Task 8) | 브라우저 |

`at`을 생략하면 서버 시계를 쓴다.

### `POST /v1/wiper-events`

요청:

```json
{
  "events": [
    {"vid": "3f2a…", "ts": "2022-08-08T21:30:00+09:00", "lat": 37.4976, "lng": 127.0276,
     "switch": "CONTINUOUS_LEVEL_3", "state": "ON"},
    {"vid": "9c11…", "ts": "2022-08-08T21:30:05+09:00", "lat": 37.4981, "lng": 127.0290,
     "switch": "AUTO", "state": "ON", "auto_wpm": 22}
  ]
}
```

| 필드 | 뜻 |
|---|---|
| `vid` | 임시 차량 ID. 15분마다 바뀌는 임의 UUID |
| `ts` | 이벤트 시각. 라이브 모드 앱은 마지막으로 받은 `/v1/clock` 값을 쓴다 |
| `lat`, `lng` | 위치 (WGS84) |
| `switch` | 와이퍼 스위치. `OFF`, `AUTO`, `MIST`, `INTERMITTENT_LEVEL_1`~`_5`, `CONTINUOUS_LEVEL_1`~`_5`, `SERVICE`, `OTHER` (Pleos Vehicle SDK 값 그대로) |
| `state` | 와이퍼 상태. `ON`, `OFF`, `SERVICE`, `OTHER` |
| `auto_wpm` | `switch`가 `AUTO`일 때만, 선택. 최근 60초 동안 와이퍼가 움직인 횟수(계속 켜져 있으면 40) |

응답 `202`:

```json
{"accepted": 2, "rejected": 0}
```

- `ts`가 서버 시계보다 5분 넘게 미래인 이벤트는 거부한다(`rejected`에 센다).
- 보내는 쪽은 원시 값만 보낸다. 세기 계산은 서버에서만 한다. `MIST`, `SERVICE`, `OTHER`와 모르는 값은 받되 세기 계산에서 빠진다.
- 배치를 받을 때마다 서버 시계 기준 2시간보다 오래된 원시 이벤트를 지운다.

### `GET /v1/clock`

```json
{"now": "2022-08-08T21:30:05+09:00"}
```

이벤트를 하나도 받지 않았으면 `{"now": null}`. 라이브 모드 앱은 `null`이면 이벤트를 보내지 않는다.

### `GET /v1/rain-map`

```json
{
  "at": "2022-08-08T21:30:05+09:00",
  "cell": {"lat": 0.0045, "lng": 0.0057},
  "cells": [CellEstimate, …]
}
```

`CellEstimate` (Task 2):

```json
{
  "row": 8332, "col": 22285, "lat": 37.49625, "lng": 127.02735,
  "mmph": 25.0, "acc1h_mm": 18.5, "vehicles": 4, "saturated_min": 0,
  "bins": [{"start": "2022-08-08T20:30:05+09:00", "mmph": 10.0, "saturated": false}, …]
}
```

| 필드 | 뜻 |
|---|---|
| `row`, `col` | 칸 번호. 위도 0.0045° × 경도 0.0057°(약 500 m) 격자, `row = floor(lat / 0.0045)`, `col = floor(lng / 0.0057)` |
| `lat`, `lng` | 칸 중심 |
| `mmph` | 최근 10분, 서로 다른 차 3대 이상의 최신 세기 중앙값(mm/h). 상한 60 |
| `acc1h_mm` | 직전 10분 구간 6개의 누적(mm). 차 3대 미만 구간은 0 |
| `vehicles` | 최근 10분 안에 이 칸에서 보낸 서로 다른 차 수 |
| `saturated_min` | 가장 최근 구간부터 연속으로 포화(구간의 차 절반 이상이 수동 연속 5단)인 시간(분) |
| `bins` | 직전 10분 구간 6개(오래된 것부터). 차 3대 미만 구간은 `mmph: null` |

최근 10분 안에 서로 다른 차가 3대 미만인 칸은 `cells`에 나오지 않는다.

### `GET /v1/underpass-risk`

```json
{
  "at": "2022-08-08T21:30:05+09:00",
  "underpasses": [UnderpassRisk, …]
}
```

`UnderpassRisk` (Task 3, Task 4c에서 `reasons`·`alerts` 추가):

```json
{
  "id": "gaepo", "name": "개포지하차도", "lat": 37.475985, "lng": 127.058793,
  "level": "DANGER", "mmph_10m": 60.0, "acc1h_mm": 52.0,
  "bypass_waypoint": {"lat": 37.475909, "lng": 127.058771},
  "reasons": ["vehicles", "saturated", "kma_warning"],
  "alerts": [{"sent_at": "2022-08-08T21:20:00+09:00", "text": "…"}]
}
```

| 필드 | 뜻 |
|---|---|
| `id`, `name`, `lat`, `lng`, `bypass_waypoint` | `shared/underpasses_gangnam.json` 그대로. `bypass_waypoint`는 없으면 `null` |
| `level` | `NONE` < `CAUTION` < `WARNING` < `DANGER` |
| `mmph_10m`, `acc1h_mm` | 반경 1 km 안 칸들의 최대 세기·최대 1시간 누적 |
| `reasons` | `level`을 낸 근거. `vehicles`, `saturated`, `radar`, `kma_warning`, `alert_closure`, `alert_rain` 중 NONE이 아닌 단계를 낸 것만 |
| `alerts` | 이 지하차도에 연결된 최근 2시간 재난문자 |

단계 기준(와이퍼·레이더 공통, `base_risk: "high"`면 모든 기준 × 0.75):

| 단계 | 세기(mm/h) | 또는 1시간 누적(mm) |
|---|---|---|
| CAUTION | ≥ 20 | ≥ 30 |
| WARNING | ≥ 40 | ≥ 50 |
| DANGER | ≥ 60 | ≥ 80 |

외부 자료는 위험을 올리기만 하고 내리지 않는다. 최종 단계는 출처별 단계의 최댓값이다. 레이더만이면 최대 WARNING, 호우특보는 CAUTION, 재난문자 통제는 DANGER, 재난문자 호우 안내는 CAUTION이다(설계 6.10.4).

### `GET /v1/external` (Task 4c)

```json
{
  "warnings": [{"area_code": "…", "area_name": "서울", "stress": "WARNING",
                "start": "2022-08-08T18:00:00+09:00", "end": null}],
  "alerts": [{"sent_at": "2022-08-08T18:17:00+09:00", "regions": ["서울특별시"],
              "text": "…", "source_url": "https://…"}]
}
```

`warnings`는 `at`에 발효 중인 호우특보(`stress`: `ADVISORY` 주의보, `WARNING` 경보), `alerts`는 `at` 이전 2시간 안의 재난문자다. 서버 환경 변수 `WIPERNET_EXTERNAL=replay|live|off`(기본 `replay`)로 출처를 고른다. `off`면 둘 다 빈 목록이다.

### `GET /v1/debug/vehicles`

```json
{"vehicles": [{"vid": "3f2a…", "ts": "2022-08-08T21:30:00+09:00", "lat": 37.4976, "lng": 127.0276,
               "switch": "CONTINUOUS_LEVEL_3", "state": "ON"}]}
```

서버 시계 기준 최근 5분 안에 이벤트를 보낸 `vid`의 마지막 이벤트. 뷰어 전용이다.

### `POST /v1/reset`

서버 상태(이벤트, 서버 시계)를 모두 지운다. 응답 `200` `{"ok": true}`.
