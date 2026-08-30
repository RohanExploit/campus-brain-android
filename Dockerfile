FROM python:3.11-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
RUN pip install fastapi uvicorn pydantic python-telegram-bot

COPY . .

# Expose API port
EXPOSE 8000
# Expose WhatsApp Webhook port
EXPOSE 8001

# The default command will be overridden by docker-compose for different services
CMD ["uvicorn", "api.main:app", "--host", "0.0.0.0", "--port", "8000"]
