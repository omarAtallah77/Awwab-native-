This folder should contain the TFLite model used by PoseAnalyzer.

Required path and filename:
  app/src/main/assets/model/yolov8n-pose_float32.tflite

Notes:
- Place the .tflite file exactly at the path above.
- The model should accept input 640x640 RGB float32 and output an array shaped like [1][56][8400] as the analyzer expects.
- If you have a different model, update MODEL_ASSET_PATH in posedetection.kt accordingly.

After adding the model, rebuild the app and run it. If the model loads successfully, you'll see pose labels in the UI overlay.

