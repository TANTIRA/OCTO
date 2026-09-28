FROM python:3.12-slim

WORKDIR /app

COPY pyproject.toml ./
COPY octo_agents ./octo_agents
COPY models.yaml ./

RUN pip install --no-cache-dir .

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8080/healthz', timeout=4)"

CMD ["uvicorn", "octo_agents.server:app", "--host", "0.0.0.0", "--port", "8080"]
