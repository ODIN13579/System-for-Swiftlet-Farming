import pytest

from src.counting import EntryExitCounter, resolve_counting_line


def test_outside_to_inside_counts_entry():
    counter = EntryExitCounter((100, 0, 100, 200), inside_direction=-1,
                               hysteresis_px=1, cooldown_frames=0)
    assert counter.update(1, 90, 50, frame_index=1) is None
    assert counter.update(1, 110, 50, frame_index=2) == "ENTRY"
    assert counter.get_counts() == {"entry": 1, "exit": 0}


def test_inside_to_outside_counts_exit():
    counter = EntryExitCounter((100, 0, 100, 200), inside_direction=-1,
                               hysteresis_px=1, cooldown_frames=0)
    assert counter.update(1, 110, 50, frame_index=1) is None
    assert counter.update(1, 90, 50, frame_index=2) == "EXIT"
    assert counter.get_counts() == {"entry": 0, "exit": 1}


def test_same_side_does_not_count():
    counter = EntryExitCounter((100, 0, 100, 200), inside_direction=-1,
                               hysteresis_px=1, cooldown_frames=0)
    counter.update(1, 80, 50, frame_index=1)
    assert counter.update(1, 85, 50, frame_index=2) is None
    assert counter.get_counts() == {"entry": 0, "exit": 0}


def test_hysteresis_band_does_not_change_side():
    counter = EntryExitCounter((100, 0, 100, 200), inside_direction=-1,
                               hysteresis_px=5, cooldown_frames=0)
    counter.update(1, 90, 50, frame_index=1)
    assert counter.update(1, 101, 50, frame_index=2) is None
    assert counter.update(1, 110, 50, frame_index=3) == "ENTRY"


def test_zero_length_line_is_invalid():
    with pytest.raises(ValueError):
        EntryExitCounter((100, 100, 100, 100))


def test_cooldown_uses_actual_frame_index():
    counter = EntryExitCounter((100, 0, 100, 200), inside_direction=-1,
                               hysteresis_px=1, cooldown_frames=10)
    counter.update(1, 90, 50, frame_index=1)
    assert counter.update(1, 110, 50, frame_index=2) == "ENTRY"
    assert counter.update(1, 90, 50, frame_index=3) is None
    assert counter.get_counts() == {"entry": 1, "exit": 0}

def test_line_from_another_resolution_is_rejected():
    with pytest.raises(ValueError, match="568x320"):
        resolve_counting_line({"counting_line": [960, 100, 960, 650]}, 568, 320)


@pytest.mark.parametrize("width,height", [(568, 320), (640, 480), (1280, 720), (1920, 1080)])
def test_normalized_line_scales_to_video_resolution(width, height):
    config = {"counting_line_normalized": [0.5, 0.125, 0.5, 0.875]}
    assert resolve_counting_line(config, width, height) == (width / 2, height / 8, width / 2, height * 7 / 8)
    with pytest.raises(ValueError, match="outside"):
        resolve_counting_line({"counting_line": [width + 1, 0, width + 1, height]}, width, height)


def test_crossing_extension_of_drawn_segment_does_not_count():
    counter = EntryExitCounter((100, 40, 100, 80), inside_direction=-1, hysteresis_px=1)
    assert counter.update(1, 90, 20, frame_index=1) is None
    assert counter.update(1, 110, 20, frame_index=2) is None
    assert counter.get_counts() == {"entry": 0, "exit": 0}


def test_crossing_segment_and_return_after_cooldown():
    counter = EntryExitCounter((100, 40, 100, 80), inside_direction=-1,
                               hysteresis_px=1, cooldown_frames=5)
    assert counter.update(1, 90, 0, frame_index=1) is None
    assert counter.update(1, 110, 100, frame_index=2) == "ENTRY"
    assert counter.update(1, 90, 0, frame_index=7) == "EXIT"


@pytest.mark.parametrize("line", ([0, 0, 0, 0], [0, 0, 2, 1], [0, 0, 1], [0, 0, 1, float("nan")]))
def test_invalid_normalized_line_is_rejected(line):
    with pytest.raises(ValueError):
        resolve_counting_line({"counting_line_normalized": line}, 568, 320)
