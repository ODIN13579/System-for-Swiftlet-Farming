
from collections import defaultdict
from math import hypot


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
        distance = self._signed_distance(
            float(center_x),
            float(center_y),
        )

        if abs(distance) <= self.hysteresis_px:
            return None

        side = 1 if distance > 0 else -1

        previous = self.previous_side.get(track_id)
        self.previous_side[track_id] = side

        if previous is None or previous == side:
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
