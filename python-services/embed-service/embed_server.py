from __future__ import annotations

import logging
import threading
import time

from fastapi import FastAPI, HTTPException
from fastapi.responses import JSONResponse
from pydantic import BaseModel

MODEL_NAME = "all-MiniLM-L6-v2"

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
log = logging.getLogger("embed-service")

app = FastAPI(title="embed-service")

_model = None
_model_load_ms = None
_load_lock = threading.Lock()


class EmbedIn(BaseModel):
    text: str | None = None
    texts: list[str] | None = None


def _get_model():
    global _model, _model_load_ms
    if _model is not None:
        return _model
    with _load_lock:
        if _model is not None:
            return _model
        started = time.perf_counter()
        log.info("lazy-loading model %s (first request)", MODEL_NAME)
        from sentence_transformers import SentenceTransformer
        _model = SentenceTransformer(MODEL_NAME)
        _model_load_ms = (time.perf_counter() - started) * 1000.0
        log.info("model %s loaded in %.0f ms; embeddings ready", MODEL_NAME, _model_load_ms)
    return _model


@app.get("/health")
def health():
    if _model is None:
        return JSONResponse(status_code=503, content={"status": "loading", "model": MODEL_NAME})
    return {"status": "ok", "model": MODEL_NAME}


@app.post("/embed")
def embed(body: EmbedIn):
    model = _get_model()
    if body.texts is not None:
        texts = body.texts
    elif body.text is not None:
        texts = [body.text]
    else:
        raise HTTPException(status_code=400, detail="provide 'text' or 'texts'")
    vectors = model.encode(texts, normalize_embeddings=True).tolist()
    dims = len(vectors[0])
    if len(vectors) == 1:
        return {"embedding": vectors[0], "dimensions": dims, "count": 1}
    return {"embeddings": vectors, "dimensions": dims, "count": len(vectors)}