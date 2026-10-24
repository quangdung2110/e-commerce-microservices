FROM python:3.12-alpine@sha256:b658b05ce9c4eb97ec6fc19424818a1f31993659c2b505a532f470e83807815d
WORKDIR /app
COPY deploy/checks/requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r /app/requirements.txt
COPY contracts/ /app/contracts/
COPY scripts/cd/checks.py scripts/cd/e2e.py /app/
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 HOME=/tmp
USER 10001:10001
ENTRYPOINT ["python", "/app/checks.py"]
