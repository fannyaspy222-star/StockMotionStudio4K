# StockMotion Studio 4K — AI Image-to-Video rebuild (0.4.0)

This branch starts a new AI-first workflow while preserving the original MVP ZIP and app artwork in the repository.

## Current implementation
- Select JPG, PNG, or SVG from Android's document picker.
- SVG files are rasterized locally to PNG before upload.
- Enter a motion prompt in Indonesian or English.
- Upload the image to a public Hugging Face Gradio Space running Wan 2.2 Image-to-Video.
- Queue the generation job, consume its Server-Sent Events result, download the MP4, save it to Movies/StockMotion, and preview it in the app.
- No paid API key or credit purchase is embedded or required by this prototype.

## Important free-service limits
The model runs on a shared public GPU. Hugging Face documents a daily ZeroGPU quota of 2 minutes for unauthenticated visitors and 5 minutes for free accounts; queues, cold starts, rate limits, and upstream changes can prevent a generation. This is not unlimited free video generation. The app currently makes unauthenticated calls, so it does not store a Hugging Face token in the APK. The Space may change or become unavailable without notice.

The model output is approximately 480–832 pixels on its long side, 16 FPS natively, and 3.5 seconds with the current app defaults. It is not 4K output. SVG is rasterized before upload; generated video does not preserve vector editability. Images are uploaded to an external public AI service for processing; do not upload private or sensitive images.

## Build
GitHub Actions extracts the preserved StockMotionStudio4K-MVP03.3-layout-ratio-source.zip, overlays the 0.4.0 rebuild files from .rebuild/, then runs assembleDebug. This keeps the previous source archive and artwork unchanged.

## Verification status
Source has been prepared for CI. A successful GitHub Actions build and a real-device generation test are still required before calling the APK verified. No guarantee is made that the public GPU service will always accept requests.
