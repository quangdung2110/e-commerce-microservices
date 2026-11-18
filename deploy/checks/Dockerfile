FROM python:3.12-alpine@sha256:c5ba3deef00c7758561a0f03c26f44f10589263d40e732b8748e0d1f38f6dd89
RUN apk upgrade --no-cache
WORKDIR /app
COPY deploy/checks/requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r /app/requirements.txt
COPY contracts/ /app/contracts/
COPY scripts/cd/checks.py scripts/cd/e2e.py /app/
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 HOME=/tmp
USER 10001:10001
ENTRYPOINT ["python", "/app/checks.py"]
