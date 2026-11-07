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
| `GET /v1/external?at=` | 발효 중 특보와 최근 재난문자 | 뷰어 |
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
| `vid` | 임시 차량 ID. 12~18분 사이 무작위 간격으로 바뀌는 임의 UUID(설계 6.11) |
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
| `vehicles` | 최근 10분 안에 이 칸에서 세기를 낼 수 있는 값(`MIST`·`SERVICE`·`OTHER` 제외)을 보낸 서로 다른 차 수 |
| `saturated_min` | 가장 최근 구간부터 연속으로 포화(구간의 차 절반 이상이 수동 연속 5단)인 시간(분) |
| `bins` | `at`에서 거꾸로 자른 10분 구간 6개(오래된 것부터). `start`는 구간 시작(그 시각은 포함하지 않음). 차 3대 미만 구간은 `mmph: null` |

최근 10분 안에 서로 다른 차가 3대 미만인 칸은 `cells`에 나오지 않는다.

### `GET /v1/underpass-risk`

```json
{
  "at": "2022-08-08T21:30:05+09:00",
  "underpasses": [UnderpassRisk, …]
}
```

`UnderpassRisk`:

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
| `mmph_10m`, `acc1h_mm` | 반경 1 km 안 칸들의 최대 세기·최대 1시간 누적. 와이퍼(포화 구간은 레이더로 바꾼 뒤)와 레이더 중 큰 값 |
| `reasons` | NONE보다 높은 단계를 낸 출처. 이 순서로 `vehicles`, `saturated`, `radar`, `kma_warning`, `alert_closure`, `alert_rain`. 최종 단계보다 낮은 단계를 낸 출처도 담는다 |
| `alerts` | 이 지하차도에 연결된 최근 2시간 재난문자 |

단계 기준(와이퍼·레이더 공통, `base_risk: "high"`면 모든 기준 × 0.75):

| 단계 | 세기(mm/h) | 또는 1시간 누적(mm) |
|---|---|---|
| CAUTION | ≥ 20 | ≥ 30 |
| WARNING | ≥ 40 | ≥ 50 |
| DANGER | ≥ 60 | ≥ 80 |

외부 자료는 위험을 올리기만 하고 내리지 않는다. 최종 단계는 출처별 단계의 최댓값이다(설계 6.10.4).

| 출처 | 단계 | `reasons` |
|---|---|---|
| 와이퍼. 포화 구간은 그 구간이 끝난 시각의 레이더 값이 더 크면 그 값으로 바꿔 세기·누적을 다시 계산 | 위 기준 그대로 | `vehicles`. 반경 안 칸이 30분 넘게 포화면 `saturated`도 |
| 레이더만 (반경 안 레이더 칸, 누적은 10분 구간 6개) | 위 기준, 최대 WARNING | `radar` |
| 지하차도 특보 구역(`warning_area`)의 호우주의보·경보 | CAUTION | `kma_warning` |
| 최근 2시간 재난문자: 지하차도 이름 + 통제·침수 낱말 | DANGER | `alert_closure` |
| 최근 2시간 재난문자: 그 구 또는 서울특별시 전체의 호우·폭우·침수 안내 | CAUTION | `alert_rain` |

서버 시계가 아직 없으면(이벤트를 받은 적이 없고 `at`도 없으면) `at: null`이고 모든 지하차도가 NONE이다.

### `GET /v1/external`

```json
{
  "at": "2022-08-09T00:30:00+09:00",
  "warnings": [{"area_code": "L1100100", "area_name": "서울동남권", "stress": "WARNING",
                "start": "2022-08-08T12:50:00+09:00", "end": null}],
  "alerts": [{"sent_at": "2022-08-09T00:23:00+09:00", "regions": ["서울특별시 강남구"],
              "text": "[강남구청] 대모산 및 양재천 인근지역, …", "source_url": "http://…"}]
}
```

`warnings`는 `at`에 발효 중인 호우특보(`stress`: `ADVISORY` 주의보, `WARNING` 경보)다. 기록 재생에서는 `at`에 아직 모르는 해제 시각을 감춰 `end`가 `null`이다. `alerts`는 `at` 이전 2시간 안의 재난문자다. `at`이 없으면 서버 시계를 쓰고, 서버 시계도 없으면 둘 다 빈 목록이다. 서버 환경 변수 `WIPERNET_EXTERNAL=replay|live|off`(기본 `replay`)로 출처를 고른다. `live`는 `KMA_API_HUBKEY`가 없으면 서버가 시작하지 않는다. 실시간 재난문자는 받지 않아 `alerts`가 늘 빈 목록이다(`docs/setup-status.md`). `off`면 둘 다 빈 목록이다.

### `GET /v1/debug/vehicles`

```json
{"vehicles": [{"vid": "3f2a…", "ts": "2022-08-08T21:30:00+09:00", "lat": 37.4976, "lng": 127.0276,
               "switch": "CONTINUOUS_LEVEL_3", "state": "ON", "auto_wpm": null}]}
```

서버 시계 기준 최근 5분 안에 이벤트를 보낸 `vid`의 마지막 이벤트. 뷰어 전용이다. 임시 ID가 12~18분마다 바뀌므로 최근 5분 안에 ID가 바뀐 차는 두 번 나온다. 차 한 대씩 위치를 내보내므로 배포 서버에서는 끈다(설계 6.11).

### `GET /viewer`

시뮬레이션 뷰어 HTML(Task 8). `?density=current|future`를 붙이면 왼쪽 위에 밀도 이름을 보여 준다(서버는 밀도를 모른다). 위 `/v1/*`를 1초마다 읽는다. 배경 지도는 OSM 표준 타일이라 데모·개발 전용이다.

### `POST /v1/reset`

서버 상태(이벤트, 서버 시계)를 모두 지운다. 응답 `200` `{"ok": true}`.
