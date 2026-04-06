# 🤖 AI Agent Web Application (RAG Platform)

Hệ thống quản lý tài liệu và trợ lý AI doanh nghiệp, tích hợp **RAG (Retrieval-Augmented Generation)** trên Spring Boot.

---

# 🚀 1. Tổng quan

Ứng dụng cho phép:

- Chat AI dựa trên tài liệu nội bộ (RAG)
- Upload & index tài liệu (PDF, DOCX)
- Phân quyền theo Role + Department + Project
- Đăng nhập bằng Google OAuth2

---

# ⚙️ 2. Tech Stack

- Java 17, Spring Boot 3.2.5
- Spring AI (Gemini)
- MySQL 8
- Qdrant (Vector DB)
- Spring Security + OAuth2
- Apache Tika

---

# 📦 3. Setup nhanh (QUAN TRỌNG NHẤT)

## 🔥 Yêu cầu

- Java 17+
- Maven
- Docker
- MySQL 8
- tạo folder uploads trong AI_Agent/uploads

---

## ⚡ 4 bước chạy project

### 1. Clone project

```bash
git clone <repo-url>
cd AI_Agent
```

### 2. Tạo file .env

Copy file mẫu:

```bash
cp .env.example .env
```

### 3. Cấu hình .env

Mở file .env và điền:

```bash
# ========================
# Database
# ========================
DB_URL=jdbc:mysql://localhost:3306/ai_agent?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true
DB_USERNAME=root
DB_PASSWORD=123456

# ========================
# Google OAuth
# ========================
GG_CLIENT_ID=your_client_id
GG_CLIENT_SECRET=your_client_secret

# ========================
# JWT
# ========================
JWT_SECRET=your_secret

# ========================
# Gemini AI
# ========================
GEMINI_API_KEY=your_api_key
GEMINI_BASE_URL=https://generativelanguage.googleapis.com
```

### 4. Tạo database

Tạo database tên là ai_agent

```bash
CREATE DATABASE ai_agent;
```

Sau đó vào MySQL
Vào Server -> Data Import
Chọn Import from Self-Contained File
Chọn file database.sql
Chọn schema ai_agent
Bấm Start Import

### 5. Chạy Qdrant (bắt buộc)

```bash
docker run -p 6333:6333 -p 6334:6334 qdrant/qdrant
```

Nếu khi run project và truy cập doc nhưng lỗi không trả response đúng dù log đã lấy được doc
thì làm theo các bước bên dưới

1. Xóa collection

```bash
iwr "http://localhost:6333/collections/company_documents" -Method DELETE -UseBasicParsing
```

Ở terminal sẽ nhận được câu hỏi và chọn Yes

2. Kiểm tra đã xóa collection thành công chưa

```bash
iwr "http://localhost:6333/collections/company_documents" -Method GET -UseBasicParsing
```

Ở terminal trả về 404 - Not found --> nghĩa là đã xóa thành công

3.  Ctrl + c đoạn code bên dưới và Ctrl + V vào cuối file application.properties

```bash
app.rag.reindex-on-startup=true
```

Sau đó start lại kiểm tra log và thấy các dòng log này
[RAG-REINDEX-RUNNER] Startup re-indexing is ENABLED
[RAG-REINDEX] Scanning database for documents to re-index...
[RAG-REINDEX] Processing [1/X]: ID=..., Title='...'

Mở terminal mới và Ctrl + V đoạn code bên dưới

```bash
(Invoke-WebRequest -Uri "http://localhost:6333/collections/company_documents" -UseBasicParsing).Content
```

Kiểm tra giá trị "point_count" > 0 là OK

4. Test lại sẽ có response được trả về đúng

### NOTE: KHI RESTART LẠI XONG THÌ XÓA DÒNG app.rag.reindex-on-startup=true đi vì mặc định là đã được set là false; VÀ CHỈ BẬT KHI RAG TRẢ RESPONSE "KHÔNG ĐỦ DỮ KIỆN..." HOẶC KHI THÊM DOCS MỚI VÀO MÀ CHƯA CÓ RESPONSE

### 6. Run project

```bash
mvn spring-boot:run
```

### 7. Test project

```bash
http://localhost:8080
```

# 🔑 4. Login

- Email/Password (nếu có seed data)
- Google OAuth2

👉 Lưu ý quan trọng:

Cấu hình redirect URI trong Google Cloud Console:

```
http://localhost:8080/login/oauth2/code/google
```

# 📂 5. Cấu trúc project

```
src/main/java/com/aiagent/
├── config/
├── controller/
├── model/
├── repository/
├── rag/
├── service/
└── util/
```

# 🧠 6. RAG Flow

Ingestion

- Tika → extract text
- Chunk bằng TokenTextSplitter
- Lưu vào Qdrant + metadata
  Retrieval
- Session context
- DB keyword search
- Vector search (Qdrant)
- Fallback similarity

# 🔒 7. Security

- RBAC (Director / Manager / Employee)
- OAuth2 Google
- JWT (đang hoàn thiện)
- BCrypt password

# ⚠️ 8. Lưu ý quan trọng

❗ Không commit file .env

File .env chứa:

- DB password
- API key
- OAuth secret

👉 Đã được ignore trong .gitignore

❗ Nếu login Google lỗi

Kiểm tra:

- Client ID / Secret
- Redirect URI
- .env đã load chưa

# 🧪 9. Troubleshooting

❌ Lỗi ${DB_USERNAME}

👉 .env chưa load

❌ Lỗi connect DB

👉 check:

- MySQL đang chạy
- DB tồn tại
- password đúng
  ❌ Lỗi Qdrant

👉 đảm bảo Docker đang chạy

# 📌 10. Roadmap

- JWT Filter
- Streaming chat
- Rate limiting
- Swagger API
- Async ingestion retry

# 🧹 11. Hygiene

Các file cũ có thể remove:

- BUG_FIX_REPORT.md
- LangChain docs cũ

# 🚀 12. Version

Version: 1.0.0
Status: Stable Development
