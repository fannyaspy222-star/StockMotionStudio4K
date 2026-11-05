# StockMotion Studio 4K — AI Image-to-Video 0.4.0

This experimental rebuild keeps the original MVP 0.3.3 ZIP and brand artwork intact, and overlays a new Android screen and generation pipeline during CI.

## Prototype flow
- Pick an image from Android's document picker.
- Rasterize SVG locally to PNG; JPG/PNG are converted to PNG.
- Enter an Indonesian or English motion prompt.
- Upload to a public Hugging Face Gradio Space running Wan 2.2 Image-to-Video.
- Submit a Gradio queue job, read its Server-Sent Events completion, download the MP4, save it under Movies/StockMotion, and preview it.

## Free service limits and privacy
Hugging Face documents ZeroGPU quotas of 2 GPU minutes/day for unauthenticated users and 5 GPU minutes/day for free accounts. Quotas reset 24 hours after first GPU use. Queue delays, rate limits, cold starts, Space changes, and upstream outages can prevent generation. This is not unlimited free generation. The app uses unauthenticated requests and does not embed a private token. See https://huggingface.co/docs/hub/spaces-zerogpu and https://huggingface.co/docs/hub/spaces-api-endpoints.

The selected public Space is an upstream dependency and may change or become unavailable. The prototype requests about 3.5 seconds at the model's configured output size; it does not generate 4K. SVG input is rasterized, so output is a video rather than an editable vector. Images are uploaded to an external public AI service; do not upload private or sensitive images.

## Build
GitHub Actions extracts the preserved root-level `StockMotionStudio4K-MVP03.3-layout-ratio-source.zip`, overlays files from `.rebuild/`, then runs `assembleDebug`. The old ZIP and its logo artwork are not deleted or rewritten.

## Verification
Source integration is experimental. A green GitHub Actions build and a real-device generation test are required before treating the APK as verified. The build artifact is not proof that the public model endpoint is currently healthy.
