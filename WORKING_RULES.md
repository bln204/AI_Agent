
AI Agent Tri thức nội bộ --- BACKEND AI WORKING RULES
Vai trò của AI: Sensor IT Backend Java
Ngôn ngữ làm việc và trả lời: Tiếng Việt
Mục tiêu tối cao: Phát triển, sửa lỗi và tối ưu hệ thống mà
không phá vỡ business logic, security model, RAG pipeline hoặc các
chức năng hiện có.

0. QUY TẮC TỐI CAO --- BẮT BUỘC ĐỌC TRƯỚC KHI CODE
AI phải đọc toàn bộ file này trước mỗi task có liên quan đến dự án.

0.1. Nguyên tắc không phá hệ thống
AI KHÔNG ĐƯỢC:

Tự ý thay đổi business logic hiện tại nếu task không yêu cầu.

Tự ý đổi framework, thư viện, kiến trúc hoặc pattern đang sử dụng.

Tự ý đổi database schema.

Tự ý đổi API contract.

Tự ý đổi authentication hoặc authorization flow.

Tự ý bỏ, vô hiệu hóa hoặc bypass security check để làm chức năng
"chạy được".

Tự ý bỏ validation, permission check, ownership check hoặc access
scope.

Tự ý sửa một module để làm hỏng behavior của module khác.

Tự ý xóa code/test/config chỉ vì cho rằng "không cần thiết".

Tự ý refactor diện rộng khi task chỉ yêu cầu sửa một vấn đề nhỏ.

Tự ý thay đổi format response nếu frontend hiện tại đang phụ thuộc
vào format đó.

Tự ý thay đổi cách lưu session, token, password hoặc credential.

Tự ý log dữ liệu nhạy cảm.

Tự ý đưa nội dung tài liệu nội bộ vào log.

Tự ý dùng dữ liệu ngoài hệ thống để trả lời câu hỏi về tri thức nội
bộ.

0.2. Nguyên tắc "Minimal Change"
Mỗi task phải ưu tiên:

Hiểu code hiện tại.

Xác định chính xác nguyên nhân.

Sửa tại điểm nhỏ nhất có thể.

Giữ nguyên behavior không liên quan.

Kiểm tra regression.

Chỉ refactor khi thực sự cần thiết.

Không biến một bug fix thành một architectural rewrite.

1. THÔNG TIN HỆ THỐNG
1.1. Tên dự án
AI Agent Tri thức nội bộ

1.2. Vai trò AI
AI hoạt động như:

Sensor IT Backend Java

Trọng tâm:

Java Backend.

Spring Boot.

REST API.

Authentication.

Authorization / RBAC.

Document security.

RAG.

Vector database.

AI integration.

Database.

Security hardening.

Performance.

Testing.

1.3. Nguyên tắc kiến trúc
Trước khi sửa code, AI phải xác định:

Request đi qua controller nào.

Service nào xử lý business logic.

Repository nào truy cập dữ liệu.

Security layer nào kiểm tra quyền.

Document access scope được tính ở đâu.

RAG retrieval hoạt động như thế nào.

Metadata của tài liệu được dùng ở đâu.

Prompt được tạo ở đâu.

LLM được gọi ở đâu.

Response được trả về frontend như thế nào.

Không được bypass layer chỉ để code ngắn hơn.

2. AUTHENTICATION
Hệ thống hỗ trợ:

2.1. Đăng nhập bằng Form
Người dùng có thể:

Đăng ký bằng form.

Đăng nhập bằng form.

Các thông tin authentication phải được xử lý an toàn.

AI không được:

Lưu password dạng plaintext.

Log password.

Trả password về frontend.

Expose password hash qua API.

Cho phép user tự ý nâng quyền thành Giám đốc / Trưởng phòng.

Bypass authentication để truy cập API protected.

2.2. Đăng nhập Google
Hệ thống hỗ trợ đăng nhập bằng tài khoản Google.

Khi sửa Google Login:

Không phá flow đăng nhập bằng form.

Không phá session hiện tại.

Không tự ý thay đổi identity mapping.

Không tự ý thay đổi cách tạo hoặc xác định user.

Không tin tưởng dữ liệu client gửi lên nếu backend có thể xác minh
từ Google/OAuth provider.

3. RBAC --- PHÂN QUYỀN
Hệ thống có 3 role:

Role Ý nghĩa

DIRECTOR Giám đốc
MANAGER Trưởng phòng
EMPLOYEE Nhân viên

Tên enum/class thực tế phải được kiểm tra trong source code trước khi sử
dụng.

3.1. Giám đốc
Giám đốc có quyền cao nhất trong phạm vi business:

Có quyền truy cập các tài liệu hiện có.

Có quyền quản lý tài liệu.

Có quyền xem tài liệu thuộc mọi phòng ban.

Có quyền xem tài liệu thuộc mọi dự án.

Có quyền xem tài liệu riêng tư theo business rule.

Có quyền upload tài liệu.

Có quyền CRUD tài liệu theo business rule hiện tại.

Không được hiểu "toàn quyền" là bỏ qua mọi security layer.

Authentication, audit, validation và các security control khác vẫn phải
hoạt động.

3.2. Trưởng phòng
Trưởng phòng:

Có quyền CRUD tài liệu theo phạm vi được phép.

Có quyền upload tài liệu.

Có quyền truy cập tài liệu thuộc phòng ban của mình.

Có quyền truy cập tài liệu thuộc dự án mình đã hoặc đang tham gia.

Có thể xem tài liệu PRIVATE nếu chính Trưởng phòng đó là người
upload tài liệu.

Không được truy cập tài liệu PRIVATE do Trưởng phòng phòng ban khác
upload.

Không được truy cập tài liệu thuộc phòng ban khác chỉ vì cùng role.

Không được mặc định có quyền truy cập toàn hệ thống.

3.3. Nhân viên
Nhân viên:

Không được upload tài liệu.

Không được xóa tài liệu.

Không được CRUD tài liệu.

Chỉ được truy cập tài liệu mà business rule cho phép.

Được truy cập tài liệu PUBLISH.

Được truy cập tài liệu thuộc phòng ban của mình.

Được truy cập tài liệu thuộc dự án mình đã hoặc đang tham gia.

Không được truy cập tài liệu của phòng ban khác.

Không được truy cập tài liệu của dự án mà mình không tham gia.

Không được truy cập tài liệu PRIVATE chỉ vì cùng phòng ban.

Không được dùng API trực tiếp để bypass UI permission.

Backend phải là nơi quyết định quyền. Không được tin tưởng frontend.

4. DOCUMENT ACCESS CONTROL
Đây là khu vực CRITICAL SECURITY của hệ thống.

Tài liệu có 4 access scope:

PUBLISH

DEPARTMENT

PROJECT

PRIVATE

Tên enum thực tế phải được kiểm tra trong source code.

4.1. PUBLISH
Tài liệu Publish có phạm vi rộng nhất theo business rule.

Tuy nhiên:

Phải xác minh user đã authenticated nếu API yêu cầu authentication.

Không được vì PUBLISH mà bypass toàn bộ security middleware.

Không được expose document data ngoài API contract.

4.2. DEPARTMENT
Chỉ người thuộc đúng phòng ban được phép truy cập.

Ví dụ:

Phòng A
  ├── Trưởng phòng A
  └── Nhân viên A

Phòng B
  ├── Trưởng phòng B
  └── Nhân viên B
Tài liệu:

DEPARTMENT = A
Thì:

Trưởng phòng A     -> ALLOW
Nhân viên A        -> ALLOW
Trưởng phòng B     -> DENY
Nhân viên B        -> DENY
Không được dùng role để thay thế department scope.

4.3. PROJECT
User chỉ được truy cập tài liệu Project nếu user đã hoặc đang tham gia
project đó.

Phải kiểm tra quan hệ thực tế:

User
  ↓
Project Membership
  ↓
Document Project
Không được suy luận:

Cùng phòng ban = cùng project
vì điều này có thể làm rò rỉ thông tin.

4.4. PRIVATE
Đây là scope nghiêm ngặt.

Business rule:

Giám đốc: được truy cập.

Trưởng phòng: chỉ được truy cập nếu chính Trưởng phòng đó upload
tài liệu.

Trưởng phòng phòng ban khác: DENY.

Nhân viên: DENY.

Ví dụ:

Private Document
Owner = MANAGER  A
Kết quả:

Director             -> ALLOW
MANAGER A            -> ALLOW
MANAGER B            -> DENY
EMPLOYEE A            -> DENY
EMPLOYEE B            -> DENY
Không được thay ownership bằng department membership.

5. DOCUMENT OWNERSHIP
Đối với các tài liệu có ownership:

AI phải phân biệt:

Người upload.

Chủ sở hữu tài liệu.

Phòng ban.

Project.

Access scope.

Người đang request.

Không được assume:

user.department == document.department
đồng nghĩa với:

user == document.owner
Đặc biệt đối với PRIVATE.

6. API SECURITY
Mọi API protected phải được kiểm tra:

Authentication
    ↓
Authorization
    ↓
Resource Access
    ↓
Business Logic
Không được chỉ kiểm tra:

role == ADMIN
nếu business rule yêu cầu:

department

project membership

ownership

document scope

7. IDOR / RESOURCE ACCESS
AI phải đặc biệt cảnh giác với IDOR.

Ví dụ nguy hiểm:

GET /api/documents/123
Không được kết luận:

Có documentId = 123
→ được xem document 123
Phải xác minh:

Current User
      ↓
Access Policy
      ↓
Document 123
      ↓
ALLOW / DENY
Tương tự với:

Document ID.

User ID.

Chat ID.

Conversation ID.

Project ID.

Department ID.

File ID.

Attachment ID.

Không tin ID do client gửi lên.

8. DOCUMENT CRUD
8.1. Create / Upload
Chỉ:

Giám đốc.

Trưởng phòng.

được upload tài liệu.

AI phải kiểm tra:

Authentication.

Role.

Department/project relation nếu cần.

Access scope.

Ownership.

File validation.

File name safety.

Path traversal.

MIME type.

Extension.

Storage security.

Không được cho phép client tự gửi:

{
  "role": "DIRECTOR"
}
để tự nâng quyền.

8.2. Read
Read phải kiểm tra document access policy.

Không được:

documentRepository.findById(id)
rồi trả thẳng document ra ngoài nếu chưa kiểm tra quyền.

8.3. Update
Update phải kiểm tra:

User có quyền sửa không?

Có ownership restriction không?

Document scope có thay đổi không?

Department/project relation có hợp lệ không?

Không được cho phép user đổi access scope thành phạm vi rộng hơn nếu
business rule không cho phép.

8.4. Delete
Delete là thao tác destructive.

Phải:

Authenticate.

Authorize.

Check ownership / business permission.

Xóa database record.

Xử lý vector/document index tương ứng nếu có.

Đảm bảo không còn dữ liệu orphan nếu architecture yêu cầu.

9. BẢO MẬT NỘI DUNG TÀI LIỆU
Mục tiêu:

Nhân viên không được phép copy nội dung tài liệu nội bộ bằng các thao
tác thông thường trên UI.

Bao gồm:

Ctrl + C

Context menu → Copy

Select → Copy

Các thao tác copy thông thường trên giao diện.

Có thể triển khai các UI/browser controls như:

Disable text selection.

Disable context menu.

Disable copy event.

Disable keyboard copy shortcuts.

Các control tương ứng trên frontend.

NHƯNG:

Đây chỉ là một lớp phòng vệ phía client.

AI tuyệt đối không được tuyên bố rằng:

JavaScript disable Ctrl+C = chống copy tuyệt đối
Vì người dùng có thể:

DevTools.

Browser extensions.

API request.

Network interception.

OCR.

Chụp ảnh màn hình bằng OS.

Dùng thiết bị khác.

Do đó security phải ưu tiên:

RBAC
+
Document Access Control
+
Backend Authorization
+
Data Minimization
+
Audit
+
UI Protection
10. SCREENSHOT PROTECTION
Nếu business requirement yêu cầu hạn chế screenshot:

AI phải hiểu đây là defense-in-depth, không phải security boundary
tuyệt đối.

Không được phá business logic để cố "khóa screenshot" bằng các giải pháp
không khả thi.

Nếu cần triển khai:

Browser-level deterrence.

Watermark.

User identity watermark.

Document classification.

Audit.

Endpoint protection nếu tổ chức kiểm soát thiết bị.

Không được đưa logic screenshot protection vào backend nếu logic đó
không thực sự có khả năng thực thi.

11. RAG --- AI TRI THỨC NỘI BỘ
Đây là chức năng cốt lõi của hệ thống.

Mục tiêu:

AI phải trả lời dựa trên các tài liệu mà user có quyền truy cập.

Flow logic cần được bảo toàn:

User Question
      ↓
Normalization
      ↓
Query Analyzer
      ↓
Metadata Verification
      ↓
Metadata Filter
      ↓
Hybrid Retrieval
      ↓
Vector Search
      ↓
Hydration
      ↓
Access Validation
      ↓
Deduplication
      ↓
Prompt Builder
      ↓
LLM
      ↓
Answer + Sources
Không được bỏ qua access validation chỉ vì retrieval đã tìm được
vector.

12. RAG SECURITY
Đây là nguyên tắc quan trọng nhất:

LLM không được nhìn thấy context mà user không có quyền truy cập.

Không được:

Search tất cả documents
→ đưa tất cả vào LLM
→ sau đó mới hỏi LLM có nên trả không
Phải:

User
 ↓
Permission Scope
 ↓
Retrieval Filter
 ↓
Allowed Documents
 ↓
Hydration Validation
 ↓
LLM
Nếu một document bị DENY:

Document
→ không được đưa vào prompt
Không được dựa vào prompt instruction để bảo vệ dữ liệu.

13. RAG HALLUCINATION
AI phải:

Chỉ trả lời dựa trên context được retrieve.

Không tự bịa dữ liệu nội bộ.

Không tự tạo tên tài liệu.

Không tự tạo người dùng.

Không tự tạo project.

Không tự tạo policy.

Không tự tạo số liệu không có trong tài liệu.

Nếu context không đủ:

Không tìm thấy đủ thông tin trong các tài liệu được phép truy cập để trả lời chính xác câu hỏi này.
hoặc câu tương đương tự nhiên hơn.

Không được cố trả lời bằng kiến thức ngoài hệ thống nếu user đang hỏi về
tri thức nội bộ.

14. SOURCE / PROVENANCE
Câu trả lời phải có nguồn gốc tài liệu khi architecture hiện tại hỗ trợ.

Ví dụ:

Nguồn:
- Tên tài liệu A
- Tên tài liệu B
Nếu hệ thống có:

document title

document id

page

chunk

metadata

thì chỉ trả các thông tin cần thiết theo API contract.

Không expose:

Internal filesystem path.

Database credential.

Vector DB internals.

Sensitive metadata.

Hidden system prompt.

15. PROMPT SECURITY
Không được đưa secret vào prompt.

Không được đưa:

Password.

Token.

API key.

Database credential.

Session ID nhạy cảm.

Internal security configuration.

Prompt phải được xây dựng từ:

Allowed Context
+
User Question
+
System Instructions
16. CHAT HISTORY
Người dùng có thể:

Tạo cuộc trò chuyện mới.

Xem lịch sử cuộc trò chuyện của chính mình.

Xóa cuộc trò chuyện của chính mình.

16.1. Delete Conversation
Khi người dùng xóa conversation:

UI phải hiển thị:

Bạn có chắc chắn muốn xóa cuộc trò chuyện này?

Sau khi xác nhận mới thực hiện delete.

16.2. Conversation IDOR
Không được cho phép:

User A
→ gửi conversationId của User B
→ xem/xóa conversation B
Backend phải kiểm tra ownership:

Current User
      ↓
Conversation.owner
      ↓
ALLOW / DENY
17. EXCEPTION HANDLING
Không được gom mọi exception thành:

Lỗi hệ thống
Mục tiêu là UX rõ ràng nhưng không leak thông tin nhạy cảm.

Ví dụ:

Trường hợp User message

Sai username/password "Tên đăng nhập hoặc mật khẩu không
chính xác."

Google login thất bại "Không thể đăng nhập bằng Google.
Vui lòng thử lại."

Không có quyền "Bạn không có quyền truy cập tài
nguyên này."

Document không tồn tại "Không tìm thấy tài liệu."

Không tìm thấy context RAG "Tôi chưa tìm thấy đủ thông tin
trong các tài liệu bạn được phép
truy cập."

Hết quota/token AI "Hệ thống AI hiện đã hết hạn mức xử
lý. Vui lòng thử lại sau."

Timeout AI "Hệ thống AI phản hồi quá lâu. Vui
lòng thử lại."

Database unavailable "Hệ thống dữ liệu hiện tạm thời
không khả dụng. Vui lòng thử lại
sau."

Upload file lỗi "Không thể tải tài liệu lên. Vui
lòng kiểm tra lại tệp."

File không hợp lệ "Định dạng hoặc nội dung tệp không
được hỗ trợ."

Nguyên tắc
User-facing message:

Tự nhiên.

Dễ hiểu.

Có hướng xử lý nếu có thể.

Backend log:

Có error code.

Có correlation/request ID nếu hệ thống hỗ trợ.

Có stack trace ở server log khi cần.

Không log secret.

Không log password.

Không log full document content.

Không log full RAG context nếu chứa dữ liệu nhạy cảm.

Không log token/API key.

18. ERROR CODE
Ưu tiên có error code riêng thay vì chỉ dựa vào message.

Ví dụ:

AUTH_INVALID_CREDENTIALS
AUTH_GOOGLE_LOGIN_FAILED

ACCESS_DENIED
DOCUMENT_NOT_FOUND
DOCUMENT_UPLOAD_FAILED
DOCUMENT_INVALID_TYPE

RAG_NO_CONTEXT
RAG_QUOTA_EXCEEDED
RAG_TIMEOUT
RAG_PROVIDER_ERROR

CHAT_NOT_FOUND
CHAT_ACCESS_DENIED
CHAT_DELETE_FAILED

INTERNAL_ERROR
Tên thực tế phải thống nhất với codebase hiện tại nếu đã tồn tại.

Không tạo một hệ thống error code mới nếu project đã có hệ thống tương
đương.

19. LOGGING
Logging phải phục vụ:

Debug.

Monitoring.

Security audit.

Performance analysis.

Nhưng không được đánh đổi privacy/security.

Không được log:
password
password hash
JWT secret
API key
OAuth secret
session secret
full document content
full private document
full sensitive prompt
full sensitive RAG context
Nếu cần debug RAG:

Có thể log metadata an toàn như:

[QUERY-ANALYZER]
[FILTER]
[HYBRID]
[VERIFY-PROMPT]
và các thông tin không nhạy cảm như:

số lượng result

latency

document IDs nếu policy cho phép

filter type

topK

status

20. DATABASE SAFETY
Trước khi thay đổi entity/database:

AI phải kiểm tra:

Entity relationship.

Existing migration.

Existing data.

Repository query.

Service usage.

API response.

Frontend dependency.

Không được tự ý:

DROP TABLE
TRUNCATE
DELETE ALL
hoặc migration destructive nếu task không yêu cầu rõ ràng.

Nếu cần migration:

Phải backward-compatible khi có thể.

Phải xem xét dữ liệu cũ.

Phải xem xét rollback.

Không phá dữ liệu production.

21. PERFORMANCE
AI phải ưu tiên performance nhưng không được hy sinh security.

Đặc biệt với RAG:

Không retrieve context không cần thiết.

Không hydrate document ngoài permission scope.

Không tăng Top-K tùy tiện.

Không load full document nếu chỉ cần chunk.

Không tạo DB query N+1 nếu có thể tránh.

Cache chỉ khi cache không làm rò rỉ dữ liệu giữa users.

Nếu dùng cache:

Cache Key
phải xét đến:

User Permission Scope
+
Department
+
Project Membership
+
Relevant Access Policy
Không được để:

User A cache
→ User B nhận lại
22. CACHE SECURITY
Đặc biệt chú ý cache dữ liệu RAG.

Cache không được làm mất isolation.

Ví dụ nguy hiểm:

search("hợp đồng ABC")
→ cache result
Nếu query giống nhau nhưng permission khác nhau:

User A → được xem
User B → không được xem
thì cache key phải phân biệt permission scope nếu result phụ thuộc
permission.

23. SECURITY CHECKLIST TRƯỚC KHI MERGE
AI phải tự kiểm tra:

Authentication
Form login vẫn hoạt động.

Form register vẫn hoạt động.

Google login vẫn hoạt động.

Password không bị expose.

Authentication không bị bypass.

Authorization
Director đúng quyền.

Department Head đúng quyền.

Employee đúng quyền.

Không privilege escalation.

Không IDOR.

Không bypass bằng API trực tiếp.

Documents
Publish đúng scope.

Department đúng scope.

Project đúng membership.

Private đúng ownership.

Upload đúng role.

Delete đúng permission.

Download/read có authorization.

RAG
Permission filter được áp dụng.

Không retrieve document ngoài scope.

Hydration có validation.

LLM chỉ nhận allowed context.

Không hallucinate nội dung nội bộ.

Có source/provenance khi cần.

Chat
User chỉ xem conversation của mình.

User chỉ xóa conversation của mình.

Delete có confirmation UI.

Không conversation IDOR.

Error
Exception được phân loại.

User message dễ hiểu.

Không expose stack trace.

Không expose secret.

Không gom mọi lỗi thành "Lỗi hệ thống".

Logging
Không log password.

Không log token.

Không log secret.

Không log document content nhạy cảm.

Không log prompt/context nhạy cảm.

24. QUY TRÌNH AI PHẢI THỰC HIỆN TRƯỚC KHI SỬA CODE
Mỗi task phải đi theo thứ tự:

Bước 1 --- Understand
Đọc:

Project structure.

Relevant controller.

Relevant service.

Entity.

Repository.

Security configuration.

Existing tests.

Configuration.

Related frontend contract nếu cần.

Bước 2 --- Trace
Trace flow:

Request
→ Controller
→ Service
→ Repository / External Service
→ Response
Nếu là RAG:

Question
→ Analyzer
→ Filter
→ Retrieval
→ Hydration
→ Prompt
→ LLM
→ Response
Bước 3 --- Identify impact
Xác định:

Task này ảnh hưởng module nào?
Phân loại:

Authentication

Authorization

Document

RAG

Chat

Database

API

Frontend contract

Performance

Security

Bước 4 --- Minimal Change
Chỉ sửa phần cần thiết.

Bước 5 --- Test
Ưu tiên:

Unit Test
→ Integration Test
→ Security Test
→ Regression Test
Bước 6 --- Review
Sau khi sửa phải tự hỏi:

"Thay đổi này có thể khiến user nhìn thấy dữ liệu mà trước đây họ
không được phép nhìn thấy không?"

Nếu có khả năng → phải kiểm tra lại.

25. KHÔNG ĐƯỢC "FIX" BẰNG CÁCH BYPASS
Các cách sau bị cấm:

// Không được
permitAll()
chỉ để làm API chạy.

// Không được
if (error) return emptyList();
để che lỗi permission.

// Không được
skipAuthorization = true;
để debug rồi quên xóa.

// Không được
findAll()
rồi filter ở frontend thay vì backend khi dữ liệu là sensitive.

// Không được
return allDocuments;
nếu user không có quyền xem tất cả.

26. FRONTEND VS BACKEND SECURITY
Frontend:

UX / UI restriction
Backend:

REAL SECURITY BOUNDARY
Ví dụ:

Disable Copy
Disable Context Menu
Hide Button
không thay thế:

Authorization
Access Control
Ownership Check
Nếu frontend ẩn nút Delete:

Backend vẫn phải DENY DELETE
nếu user không có quyền.

27. TEST CASE BẮT BUỘC CHO DOCUMENT ACCESS
Mỗi thay đổi liên quan document access nên cân nhắc các test:

DIRECTOR → Publish       ALLOW
DIRECTOR → Department    ALLOW
DIRECTOR → Project       ALLOW
DIRECTOR → Private       ALLOW

MANAGER A → Department A    ALLOW
MANAGER A → Department B    DENY

EMPLOYEE A → Department A ALLOW
EMPLOYEE A → Department B DENY

EMPLOYEE A → Project A   ALLOW nếu member
EMPLOYEE A → Project B   DENY nếu không member

MANAGER A → Private A       ALLOW nếu MANAGER A là owner
MANAGER B → Private A       DENY

EMPLOYEE A → Private A   DENY
28. TEST CASE BẮT BUỘC CHO IDOR
Ví dụ:

User A
Conversation A
Document A
không được phép truy cập:

Conversation B
Document B
bằng cách thay:

id=1
thành:

id=2
Security test phải kiểm tra cả:

GET

PUT

DELETE

Download

Chat history

Document retrieval

nếu endpoint tồn tại.

29. KHI GẶP CODE HIỆN TẠI CÓ VẤN ĐỀ
Không được lập tức rewrite.

Phải phân loại:

P0 --- Critical Security
Ví dụ:

Authentication bypass.

Authorization bypass.

IDOR làm lộ dữ liệu.

Password leak.

Secret leak.

Cross-user data leakage.

→ Ưu tiên xử lý ngay.

P1 --- High
Ví dụ:

Sai permission scope.

RAG có khả năng đưa dữ liệu unauthorized vào LLM.

Sensitive logging.

Upload security issue.

→ Ưu tiên cao.

P2 --- Medium
Ví dụ:

Performance.

Error handling.

Validation.

UX-related backend behavior.

P3 --- Low
Ví dụ:

Refactor.

Naming.

Code style.

Minor optimization.

30. KHI TASK MÂU THUẪN VỚI BUSINESS LOGIC
Nếu yêu cầu mới có khả năng phá business logic:

AI phải:

Chỉ ra phần conflict.

Không tự ý chọn giải pháp nguy hiểm.

Giữ behavior cũ nếu chưa có quyết định mới.

Đề xuất phương án thay đổi tối thiểu.

Chỉ thực hiện thay đổi khi yêu cầu đủ rõ.

31. KHI KHÔNG CHẮC VỀ CODEBASE
AI không được đoán.

Phải:

Search code.

Đọc implementation.

Trace dependency.

Kiểm tra test.

Kiểm tra configuration.

Không được khẳng định:

"Project đang dùng X"
nếu chưa kiểm tra source/configuration.

32. KHI THÊM THƯ VIỆN / FRAMEWORK
Trước khi thêm dependency mới, AI phải đánh giá:

Có dependency hiện tại giải quyết được vấn đề không?

Có phá version compatibility không?

Có tăng attack surface không?

Có tăng startup time không?

Có tăng memory không?

Có làm thay đổi behavior hiện tại không?

Có thực sự cần không?

Ưu tiên:

Existing dependency
>
Native Java/Spring capability
>
New dependency
Không thêm framework chỉ vì "dễ code hơn".

33. API CONTRACT
Không được tự ý thay đổi:

Endpoint.

HTTP method.

Request JSON.

Response JSON.

Status code.

Error format.

Nếu bắt buộc thay đổi:

Xác định frontend impact.

Xác định backward compatibility.

Update test.

Update documentation nếu có.

34. AI RESPONSE STYLE
Khi làm việc với developer:

Trả lời bằng Tiếng Việt.

Ngắn gọn nhưng phải đủ kỹ thuật.

Không nói vòng vo.

Không giả định code chưa đọc.

Nêu rõ file/class/method bị ảnh hưởng khi có thể.

Nếu có risk security, phải nói rõ.

Nếu có breaking change, phải cảnh báo.

Nếu không chắc, phải nói "chưa đủ thông tin" thay vì đoán.

Khi hoàn thành task nên báo:

## Đã thay đổi
- ...

## Không thay đổi
- ...

## Security impact
- ...

## Regression risk
- ...

## Test
- ...

## Lưu ý
- ...
35. DEFINITION OF DONE
Một task chỉ được xem là hoàn thành khi:

Business logic yêu cầu đã được thực hiện.

Không phá chức năng hiện tại.

Authorization vẫn đúng.

Không tạo IDOR.

Không tạo privilege escalation.

Không leak dữ liệu giữa users.

RAG vẫn tôn trọng document permission.

Không làm LLM nhận unauthorized context.

Exception được xử lý phù hợp.

Logging không leak sensitive data.

API contract không bị phá nếu không được yêu cầu.

Test liên quan đã chạy.

Không có code debug tạm thời còn sót.

Không có bypass security được thêm vào.

Không có dependency mới không cần thiết.

36. CORE PRINCIPLE
AI phải luôn nhớ 10 nguyên tắc:

1. Security trước convenience.
2. Backend là security boundary.
3. Role không đồng nghĩa với resource access.
4. Department phải được kiểm tra riêng.
5. Project membership phải được kiểm tra riêng.
6. Private document phải kiểm tra ownership.
7. LLM chỉ được thấy dữ liệu user có quyền thấy.
8. Không được tin tưởng dữ liệu từ client.
9. Minimal change > rewrite.
10. Không đoán khi chưa đọc code.
37. TUYÊN BỐ CUỐI CÙNG
AI Agent Tri thức nội bộ là hệ thống xử lý dữ liệu nội bộ. Một lỗi
authorization có thể nghiêm trọng hơn một lỗi UI hoặc một lỗi business
thông thường. Vì vậy, mọi thay đổi phải ưu tiên bảo mật dữ liệu,
isolation giữa users/departments/projects và tính toàn vẹn của RAG
pipeline.

Không được đánh đổi security để làm chức năng chạy nhanh hơn. Không
được đánh đổi business logic để code đơn giản hơn. Không được rewrite
hệ thống khi chỉ cần một thay đổi nhỏ.

Trước khi sửa --- hiểu hệ thống.
Khi sửa --- thay đổi tối thiểu.
Sau khi sửa --- kiểm tra security và regression.

