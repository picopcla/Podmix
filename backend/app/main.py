from fastapi import FastAPI

from app.podmix.routes import router as podmix_router


app = FastAPI(title="Podmix Backend", version="0.1.0")


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok", "service": "podmix-backend"}


app.include_router(podmix_router, prefix="/api/podmix", tags=["podmix"])

