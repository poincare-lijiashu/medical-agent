FROM python:3.11-slim

# 构建期镜像源（国内网络默认，不依赖科学上网）：apt 与 pip 都走国内镜像。
# 有内网 apt/pypi 仓的机构用 --build-arg 覆盖即可，例如：
#   docker build --build-arg APT_MIRROR=mirrors.hospital.local --build-arg PIP_INDEX_URL=http://pypi.hospital.local/simple .
ARG APT_MIRROR=mirrors.aliyun.com
ARG PIP_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple

ENV PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1 PIP_INDEX_URL=${PIP_INDEX_URL}

WORKDIR /app

# 系统依赖：PyMuPDF/构建需要的基础库。
# Debian 12/13 的源文件是 debian.sources（deb822 格式）；老镜像可能仍是 sources.list，两者都替换。
RUN set -eux; \
    for f in /etc/apt/sources.list /etc/apt/sources.list.d/debian.sources; do \
        if [ -f "$f" ]; then sed -i "s|deb.debian.org|${APT_MIRROR}|g; s|security.debian.org|${APT_MIRROR}|g" "$f"; fi; \
    done; \
    apt-get update && apt-get install -y --no-install-recommends \
    build-essential libglib2.0-0 && rm -rf /var/lib/apt/lists/*

COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

COPY backend ./backend
# Vue3 构建产物（已入库，免 node 环境；legacy frontend/ 已删除，main.py 只读这里）
COPY frontend-vue/dist ./frontend-vue/dist
# 临床控制台（第 183 批）：/console 子路径托管，与 AI 工作台同镜像同域
COPY frontend-console/dist ./frontend-console/dist
COPY scripts ./scripts
# P0-5：容器入口脚本（按 SW_AGENT_COLLECTOR_BACKEND_SERVICES 决定是否挂 SkyWalking Python agent）
COPY deploy/docker/entrypoint.sh /app/deploy/docker/entrypoint.sh

# 非 root 运行：创建专用用户（slim 基于 Debian，自带 useradd）。应用运行时仅需写 /app/data
# （审计/用户表/队列/知识库）；/app/backend 只读（模型权重经 bind 挂载进 /app/backend/models）。
RUN useradd -m -u 10001 appuser \
    && mkdir -p /app/data \
    && chown -R appuser:appuser /app/data \
    && chmod +x /app/deploy/docker/entrypoint.sh

USER appuser

# 容器健康检查：应用监听 8001（与 EXPOSE/CMD 一致）；urlopen 失败即非零退出
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s CMD python -c "import urllib.request;urllib.request.urlopen('http://127.0.0.1:8001/healthz',timeout=3)"

# 模型权重不打进镜像：运行时以卷挂载到 /app/backend/models（见 compose 的 models 卷）
EXPOSE 8001
ENTRYPOINT ["/app/deploy/docker/entrypoint.sh"]
CMD ["python","-m","uvicorn","backend.main:app","--host","0.0.0.0","--port","8001"]
