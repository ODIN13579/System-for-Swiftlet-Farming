
from collections import defaultdict
from math import hypot, isfinite


def resolve_counting_line(config, width, height):
    """Resolve the configured line in the coordinate space of this video."""
    normalized = "counting_line_normalized" in config
    key = "counting_line_normalized" if normalized else "counting_line"
    coordinates = config.get(key)
    if not isinstance(coordinates, (list, tuple)) or len(coordinates) != 4:
        raise ValueError(f"{key} must contain four coordinates")
    frame_bounds = (width, height, width, height)
    bounds = (1, 1, 1, 1) if normalized else frame_bounds
    if not all(isinstance(value, (int, float)) and not isinstance(value, bool)
               and isfinite(value) and 0 <= value <= limit
               for value, limit in zip(coordinates, bounds)):
        raise ValueError(f"{key} is outside the {width}x{height} video; use a calibrated line")
    line = tuple(value * scale for value, scale in zip(coordinates, frame_bounds)) if normalized else tuple(coordinates)
    if line[:2] == line[2:]:
        raise ValueError("Counting line endpoints must differ")
    return line


class EntryExitCounter:
    """Đếm ENTRY/EXIT theo tracking ID khi tâm bounding box qua đường đếm."""

    def __init__(self, line, inside_direction=1, hysteresis_px=8, cooldown_frames=20):
        self.x1, self.y1, self.x2, self.y2 = map(float, line)

        if self.x1 == self.x2 and self.y1 == self.y2:
            raise ValueError("Hai đầu đường đếm phải khác nhau.")

        self.inside_direction = 1 if inside_direction >= 0 else -1
        self.hysteresis_px = max(0.0, float(hysteresis_px))
        self.cooldown_frames = max(0, int(cooldown_frames))

        self.entry_count = 0
        self.exit_count = 0
        self.previous_side = {}
        self.previous_position = {}
        self.last_count_frame = defaultdict(lambda: -10**9)
        self.frame_index = 0

    def _signed_distance(self, x, y):
        dx = self.x2 - self.x1
        dy = self.y2 - self.y1

        return (
            dx * (y - self.y1) - dy * (x - self.x1)
        ) / hypot(dx, dy)

    def update(self, track_id, center_x, center_y, frame_index=None):
        """Trả về ENTRY, EXIT hoặc None."""

        if frame_index is None:
            self.frame_index += 1
            current_frame = self.frame_index
        else:
            current_frame = int(frame_index)
            self.frame_index = max(self.frame_index, current_frame)

        track_id = int(track_id)

        # Tính khoảng cách có dấu từ tâm bounding box đến đường đếm.
        x, y = float(center_x), float(center_y)
        distance = self._signed_distance(x, y)

        if abs(distance) <= self.hysteresis_px:
            return None

        side = 1 if distance > 0 else -1

        previous = self.previous_side.get(track_id)
        previous_position = self.previous_position.get(track_id)
        self.previous_side[track_id] = side
        self.previous_position[track_id] = (x, y, distance)

        if previous is None or previous == side:
            return None

        old_x, old_y, old_distance = previous_position
        fraction = old_distance / (old_distance - distance)
        crossing_x = old_x + fraction * (x - old_x)
        crossing_y = old_y + fraction * (y - old_y)
        dx, dy = self.x2 - self.x1, self.y2 - self.y1
        projection = (crossing_x - self.x1) * dx + (crossing_y - self.y1) * dy
        if not 0 <= projection <= dx * dx + dy * dy:
            return None

        if current_frame - self.last_count_frame[track_id] < self.cooldown_frames:
            return None

        self.last_count_frame[track_id] = current_frame

        was_inside = previous == self.inside_direction
        is_inside = side == self.inside_direction

        if not was_inside and is_inside:
            self.entry_count += 1
            return "ENTRY"

        if was_inside and not is_inside:
            self.exit_count += 1
            return "EXIT"

        return None

    def get_counts(self):
        return {
            "entry": self.entry_count,
            "exit": self.exit_count,
        }
