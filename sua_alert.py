from pathlib import Path
import subprocess
import sys
from textwrap import dedent


ROOT = Path(__file__).resolve().parent
BASE = "bfc3f37"

API = "frontend/src/services/api.ts"
PAGE = "frontend/src/pages/Alerts.tsx"
DASHBOARD = "frontend/src/pages/Dashboard.tsx"
HOOK = "frontend/src/hooks/useAlerts.ts"
CONTROLLER = (
    "backend/src/main/java/com/swiftletcare/alert/AlertController.java"
)


def block(text):
    return dedent(text).strip("\n") + "\n"


def original(path):
    result = subprocess.run(
        ["git", "show", f"{BASE}:{path}"],
        cwd=ROOT,
        capture_output=True,
    )
    if result.returncode:
        sys.exit(
            f"Không đọc được {path} tại commit {BASE}.\n"
            "Kiểm tra vị trí file sua_alert.py và chạy git fetch origin."
        )
    return result.stdout.decode("utf-8").replace("\r\n", "\n")


def replace_once(text, old, new):
    if text.count(old) != 1:
        sys.exit(f"Không tìm được đúng một vị trí để sửa:\n{old[:120]}")
    return text.replace(old, new, 1)


def replace_section(text, start, end, replacement):
    first = text.index(start)
    last = text.index(end, first)
    return text[:first] + replacement + text[last:]


# ------------------------------------------------------------
# Backend: đọc danh sách, kiểm tra quyền và lưu lịch sử xử lý.
# ------------------------------------------------------------

controller_source = block("""
    package com.swiftletcare.alert;

    import com.swiftletcare.api.ApiError;
    import com.swiftletcare.api.Views;
    import com.swiftletcare.security.Access;
    import com.swiftletcare.security.Access.Permission;
    import java.sql.Timestamp;
    import java.time.Instant;
    import java.util.ArrayList;
    import java.util.List;
    import org.springframework.jdbc.core.JdbcTemplate;
    import org.springframework.jdbc.core.RowMapper;
    import org.springframework.security.core.annotation.AuthenticationPrincipal;
    import org.springframework.security.oauth2.jwt.Jwt;
    import org.springframework.transaction.annotation.Isolation;
    import org.springframework.transaction.annotation.Transactional;
    import org.springframework.web.bind.annotation.*;

    @RestController
    @RequestMapping("/api/v1/alerts")
    public class AlertController {
      public enum Status {
        NEW, IN_PROGRESS, RESOLVED
      }

      public record Update(Status status, String note) {}

      public record Alert(
          long id,
          long zoneId,
          Long anomalyEventId,
          String type,
          String level,
          Status status,
          String message,
          Instant createdAt,
          Instant handledAt
      ) {}

      private static final RowMapper<Alert> MAPPER = (row, index) -> {
        Timestamp handled = row.getTimestamp("handled_at");
        return new Alert(
            row.getLong("id"),
            row.getLong("zone_id"),
            row.getObject("anomaly_event_id", Long.class),
            row.getString("type"),
            row.getString("level"),
            Status.valueOf(row.getString("status")),
            row.getString("message"),
            row.getTimestamp("created_at").toInstant(),
            handled == null ? null : handled.toInstant()
        );
      };

      private final JdbcTemplate db;
      private final Access access;

      public AlertController(JdbcTemplate db, Access access) {
        this.db = db;
        this.access = access;
      }

      @GetMapping
      @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
      public Views.ListView<Alert> list(
          @AuthenticationPrincipal Jwt jwt,
          @RequestParam long houseId,
          @RequestParam(required = false) Long zoneId,
          @RequestParam(required = false) Status status,
          @RequestParam(defaultValue = "0") int page,
          @RequestParam(defaultValue = "20") int size
      ) {
        access.requireHouse(jwt, houseId, Permission.READ);

        if (page < 0 || size < 1 || size > 100) {
          throw ApiError.bad("Invalid pagination");
        }

        String scope = " from swiftletcare.alerts a"
            + " join swiftletcare.zones z on z.id=a.zone_id"
            + " where z.house_id=? and z.active";

        List<Object> args = new ArrayList<>();
        args.add(houseId);

        if (zoneId != null) {
          scope += " and z.id=?";
          args.add(zoneId);
        }
        if (status != null) {
          scope += " and a.status=?";
          args.add(status.name());
        }

        long count = db.queryForObject(
            "select count(*)" + scope, Long.class, args.toArray()
        );

        args.add(size);
        args.add((long) page * size);

        var items = db.query(
            "select a.*" + scope
                + " order by a.created_at desc, a.id desc limit ? offset ?",
            MAPPER,
            args.toArray()
        );

        var info = new Views.PageInfo(
            page, size, count, (int) Math.ceilDiv(count, size)
        );
        return new Views.ListView<>(items, info);
      }

      private Alert lookup(long id, boolean lock) {
        String sql = "select * from swiftletcare.alerts where id=?";
        if (lock) sql += " for update";

        return db.query(sql, MAPPER, id)
            .stream()
            .findFirst()
            .orElseThrow(ApiError::missing);
      }

      @PatchMapping("/{id}/status")
      @Transactional
      public Alert update(
          @AuthenticationPrincipal Jwt jwt,
          @PathVariable long id,
          @RequestBody Update change
      ) {
        if (change.status() == null) {
          throw ApiError.bad("Missing status");
        }

        Alert current = lookup(id, true);
        access.requireZone(jwt, current.zoneId(), Permission.WRITE);

        if (current.status() == change.status()) {
          return current;
        }

        String status = change.status().name();
        db.update(
            "update swiftletcare.alerts set status=?,"
                + " handled_at=case when ?='RESOLVED'"
                + " then current_timestamp else null end where id=?",
            status, status, id
        );

        db.update(
            "insert into swiftletcare.alert_histories"
                + "(alert_id,changed_by,from_status,to_status,note)"
                + " values(?,?,?,?,?)",
            id,
            Long.parseLong(jwt.getSubject()),
            current.status().name(),
            status,
            change.note()
        );

        return lookup(id, false);
      }
    }
""")


# ------------------------------------------------------------
# Frontend: khai báo kiểu dữ liệu và hai hàm gọi REST API.
# Dùng lại request() của repo để gửi JWT đang đăng nhập.
# ------------------------------------------------------------

alert_type = block("""
    export type AlertRecord = {
      id: number
      zoneId: number
      anomalyEventId: number | null
      type: string
      level: 'WARNING' | 'CRITICAL'
      status: 'NEW' | 'IN_PROGRESS' | 'RESOLVED'
      message: string
      createdAt: string
      handledAt: string | null
    }
""")

api_methods = """
  getAlerts(zone: Zone, page: number, size: number, signal: AbortSignal) {
    const query = new URLSearchParams({
      houseId: String(zone.houseId),
      zoneId: String(zone.id),
      page: String(page),
      size: String(size),
    })
    return request<ListResponse<AlertRecord>>(
      `/api/v1/alerts?${query}`,
      { signal },
    )
  },

  updateAlertStatus(id: number, status: AlertRecord['status']) {
    return request<AlertRecord>(`/api/v1/alerts/${id}/status`, {
      method: 'PATCH',
      body: JSON.stringify({ status }),
    })
  },
"""

api_source = original(API)
api_source = replace_once(
    api_source,
    "let accessToken:",
    alert_type + "\nlet accessToken:",
)
api_source = replace_once(
    api_source,
    "export const api = {",
    "export const api = {" + api_methods,
)


# ------------------------------------------------------------
# Hook dùng chung cho trang Alert và Dashboard.
# Dashboard đọc 3 cảnh báo mới nhất. Trang Alert đọc đủ các trang.
# ------------------------------------------------------------

hook_source = block("""
    import { useEffect, useRef, useState } from 'react'
    import { api, type AlertRecord, type Zone } from '../services/api'

    const zoneId = Number(import.meta.env.VITE_ZONE_ID)
    const validZone = Number.isSafeInteger(zoneId) && zoneId > 0

    export function useAlerts(recentOnly = false) {
      const [rows, setRows] = useState<AlertRecord[]>([])
      const [zoneName, setZoneName] = useState('')
      const [error, setError] = useState(
        validZone ? '' : 'Vui lòng cấu hình VITE_ZONE_ID trong file .env của frontend.',
      )
      const [loading, setLoading] = useState(validZone)
      const revision = useRef(0)

      useEffect(() => {
        if (!validZone) return

        const controller = new AbortController()
        let timer: ReturnType<typeof setTimeout>
        let zone: Zone | undefined

        async function load() {
          const version = revision.current
          try {
            zone ??= await api.getZone(zoneId)
            if (controller.signal.aborted) return

            const size = recentOnly ? 3 : 100
            const records = new Map<number, AlertRecord>()
            let page = 0
            let pages: number

            do {
              const result = await api.getAlerts(zone, page, size, controller.signal)
              for (const row of result.items) records.set(row.id, row)
              pages = result.page.totalPages
              page++
            } while (!recentOnly && page < pages && !controller.signal.aborted)

            if (!controller.signal.aborted && version === revision.current) {
              setZoneName(zone.name)
              setRows([...records.values()])
              setError('')
            }
          } catch (e) {
            if (!controller.signal.aborted && version === revision.current) {
              setRows([])
              setError(e instanceof Error ? e.message : 'Không tải được cảnh báo.')
            }
          } finally {
            if (!controller.signal.aborted) {
              setLoading(false)
              timer = setTimeout(load, 2000)
            }
          }
        }

        void load()
        return () => {
          controller.abort()
          clearTimeout(timer)
        }
      }, [recentOnly])

      async function update(id: number, status: AlertRecord['status']) {
        const saved = await api.updateAlertStatus(id, status)
        revision.current++
        setRows(current => current.map(row => row.id === id ? saved : row))
      }

      return { rows, zoneName, error, loading, update }
    }
""")


# ------------------------------------------------------------
# Trang Alert: thay dữ liệu mẫu bằng dữ liệu từ hook.
# Tái sử dụng giao diện tìm kiếm, bộ lọc, thống kê và modal.
# ------------------------------------------------------------

page_source = original(PAGE)
page_source = (
    "import { useAlerts } from '../hooks/useAlerts'\n"
    "import type { AlertRecord } from '../services/api'\n"
    + page_source
)

page_source = replace_section(
    page_source,
    "type AlertItem =",
    "const severityClass:",
    block("""
        const statusLabel: Record<AlertRecord['status'], AlertStatus> = {
          NEW: 'Mới',
          IN_PROGRESS: 'Đang xử lý',
          RESOLVED: 'Đã xử lý',
        }
    """) + "\n",
)

page_source = replace_once(
    page_source,
    "  const [alerts, setAlerts] = useState(initialAlerts)",
    """  const { rows, zoneName, error, loading, update } = useAlerts()
  const alerts = useMemo(() => rows.map(row => ({
    id: row.id,
    title: row.type,
    description: row.message,
    type: row.type,
    severity: (row.level === 'CRITICAL' ? 'Nghiêm trọng' : 'Cảnh báo') as Severity,
    status: statusLabel[row.status],
    zone: zoneName,
    time: new Date(row.createdAt).toLocaleString('vi-VN'),
    detail: row.message,
  })), [rows, zoneName])

  const [selectedId, select] = useState<number | null>(null)
  const selectedAlert = alerts.find(row => row.id === selectedId)
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState('')""",
)

page_source = replace_once(
    page_source,
    "  const [selectedAlert, setSelectedAlert] = useState<AlertItem | null>(null)\n",
    "",
)

page_source = replace_section(
    page_source,
    "  function updateStatus(",
    "  return (\n    <>",
    """  async function updateStatus(id: number, status: AlertRecord['status']) {
    setSaving(true)
    setSaveError('')
    try {
      await update(id, status)
    } catch (e) {
      setSaveError(e instanceof Error ? e.message : 'Không lưu được trạng thái.')
    } finally {
      setSaving(false)
    }
  }

""",
)

page_source = replace_once(
    page_source,
    "Dữ liệu mô phỏng",
    "{loading ? 'Đang tải…' : error ? 'Lỗi tải dữ liệu' : zoneName}",
)
page_source = replace_once(
    page_source,
    "onClick={() => setSelectedAlert(alert)}",
    "onClick={() => { setSaveError(''); select(alert.id) }}",
)
page_source = page_source.replace("setSelectedAlert(null)", "select(null)")

page_source = replace_once(
    page_source,
    "{filteredAlerts.length === 0 && (",
    """{error && <p role="alert">{error}</p>}
          {!loading && !error && filteredAlerts.length === 0 && (""",
)

page_source = replace_once(
    page_source,
    "Cảnh báo mẫu chỉ phục vụ kiểm thử giao diện; chưa phải dữ liệu cảm biến hoặc AI thực tế.",
    "Danh sách tự cập nhật từ Backend.",
)

for label, code in [
    ("Đang xử lý", "IN_PROGRESS"),
    ("Đã xử lý", "RESOLVED"),
    ("Mới", "NEW"),
]:
    page_source = replace_once(
        page_source,
        f"onClick={{() => updateStatus(selectedAlert.id, '{label}')}}",
        "disabled={saving}\n"
        f"                  onClick={{() => updateStatus(selectedAlert.id, '{code}')}}",
    )

page_source = replace_once(
    page_source,
    "Thay đổi trạng thái hiện chỉ lưu trong bộ nhớ của trang. Khi tải lại\n"
    "              trình duyệt, dữ liệu sẽ trở về mẫu ban đầu.",
    "{saveError || (saving ? 'Đang lưu…' : '')}",
)

# Schema PostgreSQL của repo chỉ có WARNING và CRITICAL.
page_source = replace_once(page_source, " | 'Thông tin'", "")
page_source = replace_once(page_source, "  'Thông tin': 'severity-info',\n", "")
page_source = replace_once(
    page_source,
    '            <option value="Thông tin">Thông tin</option>\n',
    "",
)
page_source = replace_section(
    page_source,
    "                {alert.severity === 'Thông tin'",
    "              </div>",
    "                <AlertTriangle size={19} />\n",
)


# ------------------------------------------------------------
# Dashboard: thay ô "Chưa tích hợp API" bằng 3 cảnh báo mới nhất.
# ------------------------------------------------------------

dashboard_source = original(DASHBOARD)
dashboard_source = (
    "import { useAlerts } from '../hooks/useAlerts'\n"
    + dashboard_source
)
dashboard_source = replace_once(
    dashboard_source,
    "export default function Dashboard() {",
    "export default function Dashboard() {\n  const alerts = useAlerts(true)",
)
dashboard_source = replace_section(
    dashboard_source,
    '            <span className="subtle-badge">Chưa tích hợp API',
    "        </article>",
    """            <span className="subtle-badge">{alerts.zoneName}</span>
          </div>

          <div className="alert-list">
            {alerts.loading && <p>Đang tải cảnh báo…</p>}
            {alerts.error && <p role="alert">{alerts.error}</p>}
            {!alerts.loading && !alerts.error && alerts.rows.length === 0 && (
              <p>Chưa có cảnh báo.</p>
            )}
            {alerts.rows.map(row => (
              <div className="alert-row" key={row.id}>
                <Bell size={18} />
                <div className="alert-info">
                  <strong>{row.message}</strong>
                  <span>{formatDateTime(row.createdAt)}</span>
                </div>
              </div>
            ))}
          </div>
""",
)


# ------------------------------------------------------------
# Kiểm tra đủ 5 file trước khi ghi, tránh ghi đè code riêng của nhóm.
# Chạy lại sau khi đã áp dụng cũng được.
# ------------------------------------------------------------

outputs = {
    CONTROLLER: controller_source,
    HOOK: hook_source,
    API: api_source,
    PAGE: page_source,
    DASHBOARD: dashboard_source,
}

existing_in_base = {API, PAGE, DASHBOARD}
pending = []

for name, new_source in outputs.items():
    path = ROOT / name
    current = path.read_text(encoding="utf-8-sig") if path.exists() else None

    if current == new_source:
        continue

    expected = original(name) if name in existing_in_base else None
    if current != expected:
        sys.exit(
            f"Dừng trước khi ghi: {name} đã khác bản gốc {BASE}.\n"
            "Hãy dùng một nhánh tạo từ commit trên để tránh mất thay đổi của nhóm."
        )

    pending.append((path, new_source))

for path, source in pending:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(source.encode("utf-8"))
    print("Đã ghi:", path.relative_to(ROOT))

print(f"Hoàn tất. Đã cập nhật {len(pending)} file.")