import pytest

from wipernet_server.wiper import MAX_STAGE, auto_intensity, intensity_to_mmph, switch_intensity


def test_switch_table():
    assert switch_intensity("OFF") == 0.0
    assert switch_intensity("INTERMITTENT_LEVEL_1") == 0.10
    assert switch_intensity("CONTINUOUS_LEVEL_5") == 1.00


def test_ignored_switches():
    for s in ["MIST", "SERVICE", "OTHER", "AUTO", "GARBAGE"]:
        assert switch_intensity(s) is None


def test_auto_wipes_per_minute():
    assert auto_intensity(10) == pytest.approx(0.1 + 0.9 * 10 / 40)
    assert auto_intensity(0) == 0.0 and auto_intensity(None) is None


def test_auto_continuous_is_max_stage():
    assert auto_intensity(40) == 0.9 < MAX_STAGE  # AUTO는 포화로 세지 않는다


def test_intensity_to_mmph_interpolates():
    assert intensity_to_mmph(0.75) == 25
    assert intensity_to_mmph(0.6875) == pytest.approx(20.5)
