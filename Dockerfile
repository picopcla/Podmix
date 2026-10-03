FROM python:3.13-slim

WORKDIR /app
COPY server/requirements.txt /app/server/requirements.txt
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/* \
    && pip install --no-cache-dir -r /app/server/requirements.txt
COPY server /app/server

ENV PODMIX_HOST=0.0.0.0
ENV PODMIX_PORT=8099
VOLUME ["/app/server/data"]
EXPOSE 8099
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8099/health', timeout=3)"
CMD ["python", "/app/server/dev_server.py"]
