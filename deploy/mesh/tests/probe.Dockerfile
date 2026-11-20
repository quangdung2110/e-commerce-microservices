# Test client only; never deployed as a business service.
FROM python:3.12-slim@sha256:a6e34c598f2467ed0e9a8d349809fcd8b5c603269512df273a0bb1784edc11b1
RUN pip install --no-cache-dir grpcio==1.76.0
COPY probe.py /probe.py
USER 65532:65532
CMD ["python", "-c", "import time; time.sleep(86400)"]
