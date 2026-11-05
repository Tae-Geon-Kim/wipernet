import json
from datetime import datetime, timedelta, timezone
from pathlib import Path

import httpx

from wipernet_server.external.official import (
    Alert,
    LiveOfficialSource,
    RainWarning,
    ReplayOfficialSource,
    classify_alert,
    parse_rain_warnings,
    parse_warning_areas,
)
from wipernet_server.underpass import Underpass, load_underpasses

KST = timezone(timedelta(hours=9))
T0 = datetime(2022, 8, 8, 21, 0, tzinfo=KST)
DATA = Path(__file__).resolve().parents[1] / "data"
SHARED = Path(__file__).resolve().parents[2] / "shared" / "underpasses_gangnam.json"


def U(id="u1", name="역삼지하차도", gu="강남구"):
    return Underpass(id=id, name=name, lat=37.50, lng=127.03, base_risk="normal",
                     bypass_waypoint=None, gu=gu, warning_area="L1100100")


# --- classify_alert ---

def test_closure_alert_matches_underpass_by_name():
    a = Alert(T0, ["서울특별시 강남구"], "[강남구] 역삼지하차도 침수로 차량 통제 중", None)
    assert classify_alert(a, [U()]) == [("u1", "closure")]


def test_rain_alert_matches_by_gu():
    a = Alert(T0, ["서울특별시 강남구"], "호우경보 발효, 저지대 침수 주의", None)
    assert classify_alert(a, [U()]) == [("u1", "rain")]


def test_citywide_alert_matches_every_gu():
    a = Alert(T0, ["서울특별시"], "서울 전역 호우경보 발효", None)
    assert classify_alert(a, [U(id="u1", gu="강남구"), U(id="u2", name="사평지하도로", gu="서초구")]) == [
        ("u1", "rain"), ("u2", "rain")]


def test_rain_alert_for_other_gu_does_not_match():
    a = Alert(T0, ["서울특별시 관악구"], "도림천 범람, 저지대 침수 우려", None)
    assert classify_alert(a, [U()]) == []


def test_name_without_closure_word_falls_back_to_rain():
    a = Alert(T0, ["서울특별시 강남구"], "역삼지하차도 부근 폭우, 운전 주의", None)
    assert classify_alert(a, [U()]) == [("u1", "rain")]


def test_closure_by_name_even_outside_region():
    a = Alert(T0, ["서울특별시 서초구"], "역삼지하차도 진입금지, 우회 바랍니다", None)
    assert classify_alert(a, [U()]) == [("u1", "closure")]


def test_alert_without_rain_word_is_ignored():
    a = Alert(T0, ["서울특별시"], "코로나19 예방수칙을 지켜 주세요", None)
    assert classify_alert(a, [U()]) == []


# --- 기상청 API허브 특보 응답 해석 ---

# 2022-08-07~13 서울동남권(L1100100) 실제 응답 4줄 + 다른 구역 1줄
WRN_TEXT = """#START7777
#      TM_FC,        TM_EF,        TM_IN, STN,   REG_ID, WRN, LVL, CMD, GRD, CNT,   RPT, =
#7777END
202208071600, 202208081158, 202208071546, 109, L1100100,   R,   1,   1,  00,   4,   101, =
202208080600, 202208080800, 202208080551, 109, L1100100,   R,   2,   1,  00,   4,   101, =
202208081250, 202208081250, 202208081244, 109, L1100100,   R,   3,   6,  00,   4,   101, =
202208100230, 202208100230, 202208100222, 109, L1100100,   R,   3,   3,  00,   4,   101, =
202208080600, 202208080800, 202208080551, 109, L1100200,   R,   2,   1,  00,   4,   101, =
202208080600, 202208080800, 202208080551, 109, L1100200,   W,   2,   1,  00,   4,   101, =
"""

REG_TEXT = """#START7777
# REG_ID TM_ST        TM_ED        REG_SP   REG_UP   REG_KO---------------------------------- REG_NAME
L1100100 202005141100 210012310000 00000013 L1100000 서울동남권                               서울동남권
L1100200 202005141100 210012310000 00000013 L1100000 서울동북권                               서울동북권
L1100000 200507010000 210012310000 00000002 L1000000 서울                                     서울특별시
"""


def kst(s):
    return datetime.strptime(s, "%Y%m%d%H%M").replace(tzinfo=KST)


def test_parse_warning_areas():
    names = parse_warning_areas(REG_TEXT)
    assert names["L1100100"] == "서울동남권" and names["L1100000"] == "서울특별시"


def test_parse_rain_warnings_builds_intervals():
    ws = parse_rain_warnings(WRN_TEXT, parse_warning_areas(REG_TEXT))
    se = [w for w in ws if w.area_code == "L1100100"]
    assert se == [  # 예비특보(LVL 1)는 버린다. 주의보가 경보로 바뀌면 주의보를 닫는다
        RainWarning("L1100100", "서울동남권", "ADVISORY", kst("202208080800"), kst("202208081250")),
        RainWarning("L1100100", "서울동남권", "WARNING", kst("202208081250"), kst("202208100230")),
    ]
    ne = [w for w in ws if w.area_code == "L1100200"]
    assert ne == [RainWarning("L1100200", "서울동북권", "ADVISORY", kst("202208080800"), None)]  # 강풍(W)은 버린다


# --- 기록 재생 ---

def write_records(tmp_path, warnings, alerts):
    w, a = tmp_path / "w.json", tmp_path / "a.json"
    w.write_text(json.dumps(warnings, ensure_ascii=False), encoding="utf-8")
    a.write_text(json.dumps(alerts, ensure_ascii=False), encoding="utf-8")
    return w, a


def test_replay_hides_future_records(tmp_path):
    w, a = write_records(
        tmp_path,
        [{"area_code": "L1100100", "area_name": "서울동남권", "stress": "WARNING",
          "start": T0.isoformat(), "end": (T0 + timedelta(hours=5)).isoformat()}],
        [{"sent_at": T0.isoformat(), "regions": ["서울특별시"], "text": "호우경보", "source_url": None}],
    )
    src = ReplayOfficialSource(w, a)
    assert src.warnings(T0 - timedelta(minutes=1)) == [] and len(src.warnings(T0)) == 1
    assert src.warnings(T0 + timedelta(hours=5)) == []
    assert src.alerts(T0 - timedelta(minutes=1), since=T0 - timedelta(hours=2)) == []
    assert src.alerts(T0 + timedelta(minutes=1), since=T0 - timedelta(hours=2)) != []
    assert src.alerts(T0 + timedelta(hours=3), since=T0 + timedelta(hours=1)) == []


def test_replay_does_not_leak_future_end_time(tmp_path):
    w, a = write_records(
        tmp_path,
        [{"area_code": "L1100100", "area_name": "서울동남권", "stress": "WARNING",
          "start": T0.isoformat(), "end": (T0 + timedelta(hours=5)).isoformat()}],
        [],
    )
    [cur] = ReplayOfficialSource(w, a).warnings(T0 + timedelta(hours=1))
    assert cur.end is None  # 그 시각에는 아직 해제 시각을 모른다


# --- 실시간 (API허브) ---

def test_live_source_parses_and_caches():
    calls = []

    def fetch(url):
        calls.append(url)
        return REG_TEXT if "wrn_reg.php" in url else WRN_TEXT

    clock = [0.0]
    src = LiveOfficialSource("KEY", fetch=fetch, monotonic=lambda: clock[0])
    at = kst("202208082100")
    assert [w.stress for w in src.warnings(at) if w.area_code == "L1100100"] == ["WARNING"]
    src.warnings(at)
    assert len(calls) == 2  # 구역표 + 특보. 60초 안에는 다시 부르지 않는다
    clock[0] = 61.0
    src.warnings(at)
    assert len(calls) == 4
    assert all("authKey=KEY" in u for u in calls)
    assert src.alerts(at, since=at - timedelta(hours=2)) == []  # 재난문자 실시간은 키가 없어 비활성


def test_live_source_keeps_last_result_when_api_fails():
    ok = [True]

    def fetch(url):
        if not ok[0]:
            raise httpx.ConnectError("down")
        return REG_TEXT if "wrn_reg.php" in url else WRN_TEXT

    clock = [0.0]
    src = LiveOfficialSource("KEY", fetch=fetch, monotonic=lambda: clock[0])
    at = kst("202208082100")
    first = src.warnings(at)
    ok[0], clock[0] = False, 120.0
    assert src.warnings(at) == first


# --- 저장소에 들어 있는 2022-08-08 기록 ---

def test_recorded_warnings_cover_demo_evening():
    src = ReplayOfficialSource(DATA / "kma_warnings_20220808.json", DATA / "alerts_20220808.json")
    [w] = [w for w in src.warnings(T0) if w.area_code == "L1100100"]
    assert w.stress == "WARNING"


def test_every_underpass_has_warning_area_in_record():
    codes = {json_w["area_code"] for json_w in json.loads(
        (DATA / "kma_warnings_20220808.json").read_text(encoding="utf-8"))}
    for up in load_underpasses(SHARED):
        assert up.warning_area in codes


def test_recorded_alerts_have_sources():
    for a in json.loads((DATA / "alerts_20220808.json").read_text(encoding="utf-8")):
        assert a["source_url"], a
        datetime.fromisoformat(a["sent_at"]).utcoffset()  # 시간대가 있어야 한다
