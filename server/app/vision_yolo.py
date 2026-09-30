"""YOLOv8 object detection on JPEG frames."""

from __future__ import annotations

import logging
import threading
from typing import Any

import cv2
import numpy as np

from . import config

logger = logging.getLogger(__name__)

_model = None
_lock = threading.Lock()


def get_model():
    global _model
    if _model is None:
        with _lock:
            if _model is None:
                from ultralytics import YOLO

                logger.info("Loading YOLO model=%s", config.YOLO_MODEL)
                _model = YOLO(config.YOLO_MODEL)
    return _model


def detect_jpeg(jpeg_bytes: bytes) -> list[dict[str, Any]]:
    arr = np.frombuffer(jpeg_bytes, dtype=np.uint8)
    image = cv2.imdecode(arr, cv2.IMREAD_COLOR)
    if image is None:
        return []
    h, w = image.shape[:2]
    model = get_model()
    results = model.predict(image, conf=config.YOLO_CONF, verbose=False)
    boxes: list[dict[str, Any]] = []
    if not results:
        return boxes
    r0 = results[0]
    if r0.boxes is None:
        return boxes
    names = r0.names or {}
    for box in r0.boxes:
        xyxy = box.xyxy[0].tolist()
        x1, y1, x2, y2 = xyxy
        cls_id = int(box.cls[0]) if box.cls is not None else -1
        conf = float(box.conf[0]) if box.conf is not None else 0.0
        label = names.get(cls_id, str(cls_id))
        # Normalized 0–1 for client overlay independent of canvas size
        boxes.append(
            {
                "x": round(x1 / w, 4),
                "y": round(y1 / h, 4),
                "w": round((x2 - x1) / w, 4),
                "h": round((y2 - y1) / h, 4),
                "label": label,
                "conf": round(conf, 3),
            }
        )
    return boxes
