FROM python:3.13-slim

# Tesseract OCR (English + Hindi) and the libraries OpenCV needs. Fonts are only for generating specimens.
RUN apt-get update \
 && apt-get install -y --no-install-recommends tesseract-ocr tesseract-ocr-eng tesseract-ocr-hin libglib2.0-0 fonts-dejavu-core \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY app ./app
COPY tools ./tools

RUN useradd -r -u 10001 rhythm
USER rhythm
EXPOSE 8090
HEALTHCHECK --interval=10s --timeout=3s --start-period=20s --retries=10 \
  CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://localhost:8090/health').status==200 else 1)"
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8090", "--workers", "2"]
