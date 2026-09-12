FROM python:3.11-slim

ENV PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1 PIP_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple

WORKDIR /app

# 系统依赖：PyMuPDF/构建需要的基础库
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential libglib2.0-0 && rm -rf /var/lib/apt/lists/*

COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

COPY backend ./backend
COPY frontend-vue ./frontend-vue
COPY scripts ./scripts

# 非 root 运行：创建专用用户（slim 基于 Debian，自带 useradd）。应用运行时仅需写 /app/data
# （审计/用户表/队列/知识库）；/app/backend 只读（模型权重经 bind 挂载进 /app/backend/models）。
RUN useradd -m -u 10001 appuser \
    && mkdir -p /app/data \
    && chown -R appuser:appuser /app/data

USER appuser

# 容器健康检查：应用监听 8001（与 EXPOSE/CMD 一致）；urlopen 失败即非零退出
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s CMD python -c "import urllib.request;urllib.request.urlopen('http://127.0.0.1:8001/healthz',timeout=3)"

# 模型权重不打进镜像：运行时以卷挂载到 /app/backend/models（见 compose 的 models 卷）
EXPOSE 8001
CMD ["python","-m","uvicorn","backend.main:app","--host","0.0.0.0","--port","8001"]
