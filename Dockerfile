FROM python:3.11-slim

ENV PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1 PIP_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple

WORKDIR /app

# 系统依赖：PyMuPDF/构建需要的基础库
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential libglib2.0-0 && rm -rf /var/lib/apt/lists/*

COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

COPY backend ./backend
COPY frontend ./frontend
COPY scripts ./scripts

# 模型权重不打进镜像：运行时以卷挂载到 /app/backend/models（见 compose 的 models 卷）
EXPOSE 8001
CMD ["python","-m","uvicorn","backend.main:app","--host","0.0.0.0","--port","8001"]
