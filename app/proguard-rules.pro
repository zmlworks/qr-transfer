
# zxing-cpp's wrapper (kept by its own consumer rules) also has a CameraX entry point read(ImageProxy) that the app
# never calls; CameraX is excluded from the build.
-dontwarn androidx.camera.core.**
