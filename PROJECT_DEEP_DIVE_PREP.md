# V-Core — Ôn project cho Backend Intern/Fresher

Cập nhật ngày **18/10/2026**, đối chiếu code tại commit `aff7e93`. Tài liệu này thay bản snapshot 17/10, không phải báo cáo deployment. Mục tiêu: hiểu một luồng chính, nói ngắn và trả lời được câu hỏi tiếp theo; không cần thuộc mọi chi tiết trong repo.

## 1. Chỉ cần nhớ câu chuyện này

V-Core là web quản lý công việc dạng Kanban. Nhiều người cùng thao tác nên backend phải kiểm tra quyền, tránh ghi đè task đã bị sửa và tránh đưa quá nhiều task vào một cột.

**Điểm trình bày chính:** hai người cùng chuyển task vào cột gần đầy; backend dùng khóa cột và kiểm tra lại số lượng trong transaction. Version giải quyết việc sửa dữ liệu cũ của một task.

- Frontend: TypeScript, React/Next.js; Cloudflare Pages Worker làm BFF, tức lớp trung gian gọi backend.
- Backend: Java 21, Spring Boot; PostgreSQL lưu dữ liệu nghiệp vụ, account/session, audit và idempotency.
- Task dùng JPA, một số phần dùng JDBC để viết SQL trực tiếp. Không cần nói toàn bộ project dùng ORM.
- Quyền workspace hiện đọc trực tiếp PostgreSQL, **không còn dùng Redis role cache**. Không cần cố gán Redis vào luồng move này chỉ vì nó có trong stack/cấu hình.
- Có đăng nhập bằng session, kiểm tra CSRF và role. Public demo là chế độ VIEWER opt-in, không phải fallback owner.
- Một backend chia theo package, không phải kiến trúc microservices.

### Bài giới thiệu tiếng Anh khoảng 40 giây

> V-Core is a Kanban collaboration project built with Java, Spring Boot, PostgreSQL, and a React frontend. The main backend problem is handling concurrent task changes. When two users move tasks into a nearly full column, the service locks the target column and checks its WIP limit inside a transaction. A task version also helps reject stale updates. The project includes workspace permissions, request deduplication, and an activity feed. The flow I would like to explain is the task move operation.

WIP là giới hạn số task trong một cột. Hãy diễn đạt lại bằng lời của mình; không cần học thuộc nguyên đoạn tiếng Anh.

## 2. Luồng phải hiểu: chuyển task

1. Browser gửi target column, task version đã đọc và `Idempotency-Key` qua Worker/BFF.
2. Java kiểm tra BFF đáng tin, session/CSRF cho mutation và quyền sửa trong workspace.
3. Service mở transaction, nhận diện request đã xử lý hay chưa. Cùng key và cùng nội dung có thể trả kết quả đã lưu.
4. Với request mới, khóa cột đích; đọc task đúng workspace/project và so version.
5. Nếu chuyển sang cột khác có WIP limit, đếm task rồi kiểm tra còn chỗ không. Hợp lệ thì cập nhật task.
6. Lưu activity, outbox và kết quả idempotency trong transaction; commit rồi trả response. SSE sau đó báo UI tải lại dữ liệu.

**Ví dụ tự giải thích:** cột có limit 3, đang có 2 task. A và B chuyển hai task khác nhau vào. A lấy khóa trước và thêm task thứ ba. B chờ, sau đó đếm lại thấy cột đã đầy và nhận conflict. Đó là tình huống cụ thể, không phải tuyên bố đã chứng minh mọi lịch chạy đồng thời.

Không cần thuộc câu SQL ngay. Phải trả lời được: khóa cái gì, giữ đến khi nào, và tại sao phải đếm sau khi lấy khóa.

## 3. Tám câu hỏi cần học trước

### 1. Tại sao dùng cả lock và version?

Lock cột bảo vệ giới hạn tổng số task của cột; version bảo vệ một task khỏi cập nhật dựa trên dữ liệu cũ. Hai task khác nhau có version riêng, nên chỉ dùng version của task chưa đủ để ngăn vượt WIP.

**English:** “The column lock protects the WIP rule across multiple tasks. The task version protects one task from stale updates. They solve different problems.”

### 2. Transaction giúp gì? Nếu lưu audit lỗi thì sao?

Trong luồng move, thay đổi task, activity/outbox và response idempotency cùng transaction. Nếu có lỗi làm transaction rollback, các ghi DB đó không được commit một phần. Transaction DB không đồng nghĩa đã gửi được SSE tới browser.

**English:** “The task change and its audit records are committed together. If the transaction rolls back, the related database changes roll back as well.”

### 3. Nếu người dùng bấm lại vì mạng chậm?

Client cần giữ cùng key và payload cho cùng thao tác. Backend lưu request hash và response theo workspace/key; request giống nhau trả response đã lưu, nội dung khác bị conflict. Đây không phải khóa chống mọi double click ở frontend và không thay thế version check.

**English:** “The backend recognizes a repeated operation by its key and request hash, so retrying it does not apply the same change again.”

### 4. Làm sao tránh sửa task của workspace khác?

Backend lấy user từ session, kiểm tra membership/role trong PostgreSQL và truy vấn task theo cả workspace/project. UUID khó đoán không phải phân quyền. Biết BFF secret cũng không tự cấp quyền OWNER; public viewer không được ghi.

**English:** “The service checks current workspace membership and scopes task queries to the requested workspace and project.”

### 5. SSE dùng để làm gì? Có bảo đảm không mất event không?

SSE gửi thông báo thay đổi từ server để UI invalidate query rồi GET lại. Dữ liệu đúng nằm ở DB, không nằm trong event. Outbox hiện được đánh dấu trước send và emitter nằm trong từng process, nên có thể mất notification; reconnect chưa có cơ chế bắt kịp snapshot đầy đủ. Không nói exactly-once delivery.

**English:** “SSE is a refresh hint. The database remains the source of truth, and the current feed does not guarantee durable delivery to every browser.”

### 6. Đăng xuất hoặc bị thu hồi quyền thì stream cũ thế nào?

Với subscriber đã đăng nhập, backend kiểm tra lại membership, account và session gốc trước event/heartbeat; không chỉ kiểm tra lúc mở stream. Nếu không xác minh được quyền, stream bị đóng. Kiểm tra quyền không thể rút lại byte đã gửi trước đó và không phải cam kết thu hồi tức thời ở mọi thời điểm.

Không cần chủ động giải thích SQL session. Mức Intern chỉ cần hiểu vì sao một kết nối mở lâu phải kiểm tra lại quyền.

### 7. Admin giảm WIP thấp hơn số task đang có thì sao?

Backend khóa cột, kiểm tra version và occupancy, rồi từ chối nếu limit mới thấp hơn số task hiện có. Không tự di chuyển hoặc xóa task để đáp ứng limit. Đây là quy tắc hiện tại, không còn là phương án chưa quyết định.

### 8. Em kiểm tra concurrency bằng cách nào?

Repo có integration tests dùng PostgreSQL/Redis Testcontainers. Test khởi chạy hai move cạnh tranh và kiểm tra cả response lẫn số task cuối; test retry kiểm tra mutation/audit không bị lặp. Chỉ nói bản thân đã chạy hoặc debug test nếu thực sự làm việc đó.

**English:** “One integration test starts two competing moves and checks that only one fills the last slot. It also verifies the final database state, not just HTTP responses.”

## 4. Đọc code theo thứ tự, không đọc cả repo

| Bước | File | Đọc để trả lời |
| --- | --- | --- |
| 1 | [TaskController](backend/src/main/java/dev/vcore/task/web/TaskController.java) | Request move nhận dữ liệu nào, gọi service nào? |
| 2 | [TaskMoveService](backend/src/main/java/dev/vcore/task/application/TaskMoveService.java) | Theo `move`: quyền → idempotency → khóa → version/WIP → save → audit. |
| 3 | [WorkflowColumnRepository](backend/src/main/java/dev/vcore/task/infrastructure/WorkflowColumnRepository.java), [TaskEntity](backend/src/main/java/dev/vcore/task/domain/TaskEntity.java) | Khóa cột ở đâu? `@Version` nằm ở đâu? |
| 4 | [WorkspaceAccessService](backend/src/main/java/dev/vcore/workspace/application/WorkspaceAccessService.java) | Role lấy từ DB và VIEWER bị chặn thế nào? |
| 5 | [IdempotencyService](backend/src/main/java/dev/vcore/task/application/IdempotencyService.java) | Cùng key/cùng hash so với khác hash. |
| 6 | [Integration tests](backend/src/test/java/dev/vcore/VCoreApplicationTest.java) | Tìm ba test dưới đây, đọc setup và assertion. |
| Khi được hỏi | [WorkspaceOperationsService](backend/src/main/java/dev/vcore/workspace/application/WorkspaceOperationsService.java) | `updateWipLimit` kiểm tra occupancy. |
| Khi được hỏi | [SseSubscriptionAccess](backend/src/main/java/dev/vcore/activity/application/SseSubscriptionAccess.java), [useWorkspaceEvents](hooks/use-workspace-events.ts) | Kiểm tra lại quyền và UI refresh. |
| Khi được hỏi | [OperatorSessionController](backend/src/main/java/dev/vcore/shared/config/OperatorSessionController.java), [SecurityConfiguration](backend/src/main/java/dev/vcore/shared/config/SecurityConfiguration.java) | Login/session/CSRF; service secret khác user identity. |

Ba test đáng hiểu nhất:

- `concurrentMovesCannotExceedTheTargetColumnsWipLimit`: hai request, một chỗ trống, final count đúng.
- `retriesATaskMoveWithoutDuplicatingTheMutationOrAudit`: retry không tạo thêm mutation/audit.
- `rejectsAWipLimitBelowTheCurrentOccupancy`: admin không phá invariant bằng cách giảm limit.

Nếu hỏi security, xem thêm `aTrustedBffCannotGrantWritesByClaimingAnOwnerEmail` và `anOpenStreamStopsBeforeSendingAnEventAfterMembershipRemoval`. [Evidence](docs/evidence/remediation-2026-10-18.md) ghi các lượt kiểm tra đã chạy; test có trong code không tự chứng minh production đang chạy bản đó.

## 5. Câu hỏi tình huống: trả lời theo cách đơn giản

**UI báo 409 khi move?** Đọc thông báo để phân biệt cột đầy, version cũ hay key bị dùng sai. GET lại trạng thái và cho user quyết định thao tác mới. Nếu chỉ mất response, không tự đổi key để gửi lại cùng thao tác.

**DB đã đúng nhưng người khác chưa thấy task?** Kiểm tra SSE và query refresh; thử tải lại dữ liệu. Không kết luận DB bị mất dữ liệu chỉ từ giao diện cũ.

**Khách hàng muốn đổi chính sách WIP?** Hỏi limit theo cột hay theo người, blocked task có tính không, giảm limit dưới occupancy xử lý thế nào. Nêu policy hiện tại trước khi đề xuất thay đổi; không nhận đây là kinh nghiệm khách hàng thật.

## 6. Chỉ đọc khi được hỏi sâu

- Idempotency hiện scope theo workspace/key; có lưu `expires_at` nhưng lookup chưa thực thi expiry/cleanup. Không nói “key tự hết hạn sau 24 giờ” hoặc đã scope riêng actor/operation.
- Outbox DB bền vững không đồng nghĩa browser nhận được event; multi-instance notification và snapshot convergence còn giới hạn.
- Lock cùng cột làm các thao tác chờ nhau: đánh đổi correctness lấy một phần concurrency. Chưa có số liệu production để nói rất nhanh hoặc chịu tải lớn.
- SSE đọc lại quyền có chi phí DB; không claim hard real-time revocation. Không cần đào sâu lease/cursor/distributed messaging ở phần mở đầu.

## 7. Cách học trong khoảng 60 phút

1. **10 phút:** hiểu ví dụ cột 2/3 task; tự kể bài giới thiệu bằng tiếng Việt.
2. **20 phút:** đọc bước 1–5 của bảng code; vẽ luồng request bằng 6 ô trên giấy.
3. **15 phút:** đọc ba test; giải thích expected response và dữ liệu cuối.
4. **15 phút:** đóng tài liệu, trả lời tám câu hỏi; mở code kiểm tra chỗ mình chưa giải thích được.

Ưu tiên Java/OOP, Spring controller/service, transaction, SQL lock, version và HTTP conflict. Chưa cần học AWS/Kubernetes hay đọc mọi file frontend để trình bày project này.

### Tự kiểm tra trước phỏng vấn

- [ ] Nói được lock cột khác version task ở điểm nào, không đọc đáp án.
- [ ] Giải thích được transaction rollback và một test concurrency.
- [ ] Phân biệt BFF secret, session và workspace role.
- [ ] Nêu được một giới hạn: SSE delivery hoặc idempotency expiry.
- [ ] Có một phần thực sự hiểu/thực hành để nói về đóng góp cá nhân.

Mẫu đóng góp, **chỉ điền việc đã thực sự làm**: “Em tập trung vào [luồng cụ thể]. Em đã [đọc/viết/debug/thực hành cụ thể] và kiểm tra bằng [test cụ thể].” Nếu có AI hỗ trợ thì nói đúng phạm vi khi được hỏi; không nhận đã tự thiết kế toàn bộ hoặc có kinh nghiệm vận hành production chỉ vì code tồn tại.
