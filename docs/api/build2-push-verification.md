# BUILD 2 — xác minh trước commit/push sau fast-forward

Ngày 04/11/2026. Người dùng đã cho phép backup, fast-forward htran, khôi phục BUILD2, test lại và commit/push nếu pass.

## Tích hợp và bảo toàn thay đổi

- Backup ZIP/patch/manifest: `backend/logs/backups/20261104-171628-before-fast-forward/`, bao gồm tracked, untracked, .env riêng tư và hai reference cũ; không đưa backup vào Git.
- Stash dự phòng còn giữ: `6b96cd13c321bb12d3c3bb985a1979b468a61ff0`. Không pop/drop stash cũ của người dùng.
- htran đã fast-forward 03fa14c → 7590aec (origin/htran), không merge commit. Giữ nguyên backend/frontend/LocalData/Realtime của remote.
- Khôi phục BUILD2 bằng tracked patch và ZIP untracked; không có xung đột code. V3 và hai seed đã khớp remote, dùng bản remote và không stage lại.
- Hai reference SQL cũ bị remote xóa được giữ local dưới dạng untracked, không thêm lại trong commit. `.vscode/settings.json` thay đổi trong lúc khôi phục: giữ bản hiện tại, bản gốc nằm trong ZIP; file ngoài phạm vi, không stage.
- Nâng Java25 local ở POM/Dockerfile/scope được giữ riêng, không stage. README chỉ stage phần BUILD2; paragraph baseline JDK giữ theo parent commit. Test sau tích hợp sử dụng snapshot từ **Git index**, gồm POM Java21 của parent, để xác minh đúng nội dung định commit.

## Phạm vi và giới hạn

Commit chỉ gồm code/test/docs B2-10/11/12/13/14/17 cùng cấu hình/script test riêng và chuyển tiếp MQTT credential qua environment. `.env.example` chỉ thêm tên biến rỗng; không commit .env hoặc credential thật. Không đưa log/target/node_modules/.vscode/SQL reference cũ/frontend vào staged diff.

Đây là phần BUILD2 đã triển khai, không phải nghiệm thu hoàn tất: command dispatch/ACK/timeout, config retained/per-device ACK, event persistence, thống kê hôm nay và một số policy House vẫn còn blocker như [bảng sáu task](build2-resume-review.md). Chưa kiểm firmware/React thật. B2-15/B2-16 không được triển khai thay.

## Kiểm thử sau tích hợp

Đã chạy **clean verify trên snapshot xuất trực tiếp từ Git index** sau fast-forward, gồm LocalData/Realtime của remote và phần BUILD2 định commit. POM trong snapshot giữ Java21 của parent; không dùng override Java25 và không dùng POM Java25 còn unstaged.

Với env test trỏ DB review host 55433 và broker host 51884, lệnh Maven:

```powershell
mvn -o -f backend/logs/staged-build/20261104-172143/pom.xml "-Dmaven.repo.local=$env:USERPROFILE\.m2\repository" clean verify
```

**BUILD SUCCESS**, kết thúc 17:23:15 ngày 04/11/2026 (Asia/Saigon): **27 pass, 0 failures, 0 errors, 0 skipped**. BackendIntegrationTest 2, Build2ManagementIntegrationTest 11, IngestionIntegrationTest 13, ZoneConfigValidationTest 1. DB/broker/HTTP/WS thật; producer AI/MQTT mô phỏng; chưa chạy firmware/React thật. Flyway validate V3 hiện có, không import seed hoặc kết nối DB nghiệp vụ.

Bằng chứng local, không commit:

- `backend/logs/build2-post-ff-verify.log`.
- `backend/logs/staged-build/20261104-172143/verification-summary.json`, `index-manifest.json`.
- JUnit XML/txt và JAR trong `backend/logs/staged-build/20261104-172143/target/`.

Đối chiếu SHA256 mọi backend blob trong index với snapshot đã test: khớp. Staged diff kiểm không có .env thực, credential local, token JWT/private key, log/build output, frontend, V3/seed lặp hoặc thay đổi nâng Java25. `.env.example` chỉ thêm hai biến rỗng; credential công khai cố định trong compose/test chỉ dùng stack test riêng. `git diff --cached --check` pass; validator OpenAPI nội bộ kiểm refs/parameters/security, không thay thế official full validation.
