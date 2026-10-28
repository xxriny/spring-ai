FROM python:3.11-slim-bookworm
WORKDIR /app
ENV PYTHONUNBUFFERED=1 SERVICE_HOST=0.0.0.0 HF_HOME=/models/huggingface
COPY requirements.txt ./
RUN pip install --no-cache-dir --index-url https://download.pytorch.org/whl/cpu torch \
    && pip install --no-cache-dir -r requirements.txt
COPY server.py ./
EXPOSE 8091
CMD ["python", "server.py"]
