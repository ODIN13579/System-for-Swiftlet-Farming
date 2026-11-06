# SwiftletCare — API Security Matrix (đề xuất chờ P3/P4 phê duyệt)

**Quyết định người dùng ngày 02/11/2026:** Global `User.role=ADMIN` và account active được truy cập toàn hệ thống; không cần HouseMembership để đọc/ghi tài nguyên House khác đang active. Global OWNER không có bypass. `HouseMembership.role` OWNER/ADMIN/VIEWER chỉ cấp quyền trong House tương ứng; House ADMIN không trở thành Global ADMIN. Inactive account luôn bị từ chối. VIEWER chỉ đọc. REST/WS dùng chung `Access`. AI Service vẫn dùng credential riêng; quyền global không thay thế service key.

| Phạm vi endpoint | House OWNER | House ADMIN | House VIEWER | Mức |
|---|---|---|---|---|
| Đọc House/Zone/Device/Telemetry/Alert/CountLog/Report | Có | Có | Có | MVP |
| Tạo/sửa Zone, Device | Có | Có theo yêu cầu BUILD2 | Không | MVP |
| Cập nhật ZoneConfig | Có | Có theo yêu cầu BUILD2 | Không | MVP |
| `POST` Actuator Command | Có | Có theo yêu cầu BUILD2 | Không | MVP |
| Chuyển trạng thái Alert | Có | Đề xuất Có | Không | MVP |
| Ghi HarvestLog | Có | Đề xuất Có | Không | MVP |
| Thêm/sửa HouseMember | Có | Cần quyết định quyền Admin | Không | Extended |
| Đổi thông tin House | Có | Cần quyết định quyền Admin | Không | Extended |
| Xem các thông báo của bản thân | Có | Có | Có | Extended |
| Internal AI ingestion | Không | Không | Không | Service credential |

## Cách kiểm tra ở Spring Boot

1. JWT hợp lệ → tra `User.active` → xác định `houseId` gián tiếp qua resource.
2. Global ADMIN active được vượt House; người dùng khác cần `HouseMembership(userId,houseId,active=true)` và role đủ xem/sửa. Không tin `houseId` client gửi khi khác resource đích.
3. Request AI `/internal/ai/*` không được mở bằng quyền JWT của browser; sử dụng service authentication và kiểm tra CAMERA thực sự thuộc Zone và nguồn hợp lệ.
4. WebSocket subscribe cũng phải áp dụng cùng quyền House/Zone, kiểm tra lại khi quyền bị thu hồi.
5. Audit action sửa cấu hình, gửi lệnh, đổi Alert, thay HouseMembership; không ghi secret.

## Quyết định chưa chốt

- Global ADMIN vượt House đã được người dùng chốt ngày 02/11/2026 như trên; các quyền lifecycle House còn cần làm rõ ở dưới.
- House ADMIN có được phân quyền thành viên, chỉnh sửa House và quản lý người dùng không?
- Có thể thêm một `House` trước khi tạo Zone hay bắt buộc tạo initial Zone? Nếu giữ UML `House 1..* Zone`, cần transaction tạo đồng thời hoặc đổi multiplicity.
- Lựa chọn `X-Service-Key` cho AI chỉ là giải pháp đơn giản cho prototype; môi trường ngoài demo cần cơ chế an toàn tương ứng NFR.
