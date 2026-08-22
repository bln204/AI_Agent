import json
from sentence_transformers import SentenceTransformer
from qdrant_client import QdrantClient
from qdrant_client.models import PointStruct

# ==== CẤU HÌNH ====
QDRANT_HOST = "tokaido.proxy.rlwy.net"
QDRANT_PORT = 42508
COLLECTION_NAME = "company_documents"
JSON_FILES = [
    r"D:\Chuyen_De\AI_Agent\qdrant_dump.json",
    r"D:\Chuyen_De\AI_Agent\qdrant_points.json",
]
# ===================

print("Dang tai model embedding (lan dau se tai ve, mat vai phut)...")
model = SentenceTransformer("sentence-transformers/all-MiniLM-L6-v2")

client = QdrantClient(host=QDRANT_HOST, port=QDRANT_PORT)

all_points = []
seen_ids = set()

for file_path in JSON_FILES:
    print(f"Dang doc {file_path}...")
    with open(file_path, "r", encoding="utf-8-sig") as f:
        data = json.load(f)
    points = data["result"]["points"]
    for p in points:
        pid = p["id"]
        if pid in seen_ids:
            continue
        seen_ids.add(pid)
        payload = p["payload"]
        text = payload.get("doc_content", "")
        if not text.strip():
            continue
        all_points.append((pid, text, payload))

print(f"Tong so diem can import: {len(all_points)}")

BATCH_SIZE = 32
for i in range(0, len(all_points), BATCH_SIZE):
    batch = all_points[i:i+BATCH_SIZE]
    texts = [b[1] for b in batch]
    embeddings = model.encode(texts, show_progress_bar=False)

    qdrant_points = [
        PointStruct(
            id=batch[j][0],
            vector=embeddings[j].tolist(),
            payload=batch[j][2],
        )
        for j in range(len(batch))
    ]

    client.upsert(collection_name=COLLECTION_NAME, points=qdrant_points)
    print(f"Da import {min(i+BATCH_SIZE, len(all_points))}/{len(all_points)}...")

print("HOAN TAT!")

count = client.count(collection_name=COLLECTION_NAME)
print(f"Tong so points trong collection hien tai: {count}")