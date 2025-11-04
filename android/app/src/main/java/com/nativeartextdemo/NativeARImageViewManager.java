package com.visionar;

import android.content.Context;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.os.AsyncTask;
import android.view.Display;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.WindowManager;
import androidx.annotation.NonNull;
import android.util.Log;
import androidx.annotation.Nullable;

import com.facebook.react.uimanager.SimpleViewManager;
import com.facebook.react.uimanager.ThemedReactContext;
import com.facebook.react.uimanager.annotations.ReactProp;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.Arguments;

import com.google.ar.core.Anchor;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Camera;
import com.google.ar.core.Config;
import com.google.ar.core.Frame;
import com.google.ar.core.HitResult;
import com.google.ar.core.Plane;
import com.google.ar.core.Point;
import com.google.ar.core.Session;
import com.google.ar.core.Trackable;
import com.google.ar.core.TrackingState;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.UnavailableException;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class NativeARImageViewManager extends SimpleViewManager<GLSurfaceView> {
  public static final String REACT_CLASS = "NativeARImage";

  @Override
  public String getName() {
    return REACT_CLASS;
  }

  @Override
  @NonNull
  protected GLSurfaceView createViewInstance(@NonNull ThemedReactContext reactContext) {
    ARImageView arImageView = new ARImageView(reactContext);
    arImageView.onResume();
    return arImageView;
  }

  @ReactProp(name = "imageSource")
  public void setImageSource(GLSurfaceView view, @Nullable String imageSource) {
    if (view instanceof ARImageView) {
      ((ARImageView) view).setImageSource(imageSource);
    }
  }

  @ReactProp(name = "imageUrl")
  public void setImageUrl(GLSurfaceView view, @Nullable String imageUrl) {
    if (view instanceof ARImageView) {
      ((ARImageView) view).setImageUrl(imageUrl);
    }
  }

  @Override
  public void onDropViewInstance(@NonNull GLSurfaceView view) {
    if (view instanceof ARImageView) {
      ((ARImageView) view).onPause();
    }
    super.onDropViewInstance(view);
  }

  private static class ARImageView extends GLSurfaceView implements GLSurfaceView.Renderer {
    private Session arSession;
    private String imageSource = null;
    private String imageUrl = null;
    private Bitmap currentBitmap = null;
    private final List<Anchor> anchors = new ArrayList<>();
    private final List<float[]> anchorRotations = new ArrayList<>();
    private boolean sessionInitialized = false;
    private Exception initializationError = null;
    private int cameraTextureId = -1;
    private BackgroundRenderer backgroundRenderer;
    private boolean installRequested = false;
    private int viewportWidth = 0;
    private int viewportHeight = 0;
    private ImageBillboardRenderer imageRenderer;
    private PlaneRenderer planeRenderer;
    private volatile boolean pendingTap = false;
    private volatile float pendingTapX = 0f;
    private volatile float pendingTapY = 0f;
    private volatile boolean dragging = false;
    private volatile float dragX = 0f;
    private volatile float dragY = 0f;
    private volatile boolean rotating = false;
    private volatile float currentRotationX = 0f;
    private volatile float currentRotationY = 0f;
    private volatile float currentRotationZ = 0f;
    private volatile float lastRotationAngle = 0f;
    private volatile float lastTwoFingerX = 0f;
    private volatile float lastTwoFingerY = 0f;

    // State tracking for loading feedback
    private boolean arSessionReady = false;
    private boolean imageLoaded = false;
    private boolean planeDetected = false;
    private boolean hasEmittedPlaneDetection = false;
    private ThemedReactContext reactContext;

    public ARImageView(Context context) {
      super(context);
      this.reactContext = (ThemedReactContext) context;
      
      setEGLContextClientVersion(2);
      setEGLConfigChooser(8, 8, 8, 8, 16, 0);
      getHolder().setFormat(android.graphics.PixelFormat.TRANSLUCENT);
      setPreserveEGLContextOnPause(true);
      setRenderer(this);
      setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

      setOnTouchListener((v, event) -> {
        int action = event.getActionMasked();
        int pointerCount = event.getPointerCount();
        
        switch (action) {
          case MotionEvent.ACTION_DOWN:
            pendingTapX = event.getX();
            pendingTapY = event.getY();
            pendingTap = true;
            dragging = true;
            dragX = event.getX();
            dragY = event.getY();
            rotating = false;
            break;
            
          case MotionEvent.ACTION_POINTER_DOWN:
            if (pointerCount == 2) {
              rotating = true;
              dragging = false;
              pendingTap = false;
              lastRotationAngle = getRotationAngle(event);
              lastTwoFingerX = (event.getX(0) + event.getX(1)) / 2f;
              lastTwoFingerY = (event.getY(0) + event.getY(1)) / 2f;
            }
            break;
            
          case MotionEvent.ACTION_MOVE:
            if (pointerCount == 2 && rotating) {
              float currentTwoFingerX = (event.getX(0) + event.getX(1)) / 2f;
              float currentTwoFingerY = (event.getY(0) + event.getY(1)) / 2f;
              
              float deltaX = currentTwoFingerX - lastTwoFingerX;
              float deltaY = currentTwoFingerY - lastTwoFingerY;
              
              float currentAngle = getRotationAngle(event);
              float rotationDelta = currentAngle - lastRotationAngle;
              
              if (rotationDelta > 180) rotationDelta -= 360;
              if (rotationDelta < -180) rotationDelta += 360;
              
              currentRotationZ += rotationDelta;
              currentRotationX += deltaY * 0.5f;
              currentRotationY += deltaX * 0.5f;
              
              lastRotationAngle = currentAngle;
              lastTwoFingerX = currentTwoFingerX;
              lastTwoFingerY = currentTwoFingerY;
            } else if (pointerCount == 1 && !rotating) {
              dragging = true;
              dragX = event.getX();
              dragY = event.getY();
            }
            break;
            
          case MotionEvent.ACTION_POINTER_UP:
            if (pointerCount == 2) {
              rotating = false;
            }
            break;
            
          case MotionEvent.ACTION_UP:
          case MotionEvent.ACTION_CANCEL:
            dragging = false;
            rotating = false;
            break;
        }
        return true;
      });
    }

    // Event emitter for React Native
    private void emitAREvent(String eventType, String message) {
      if (reactContext != null) {
        WritableMap params = Arguments.createMap();
        params.putString("type", eventType);
        params.putString("message", message);
        params.putBoolean("arSessionReady", arSessionReady);
        params.putBoolean("imageLoaded", imageLoaded);
        params.putBoolean("planeDetected", planeDetected);
        
        reactContext
          .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
          .emit("onARStateChange", params);
      }
    }

    public void setImageSource(String source) {
      this.imageSource = source;
      this.imageUrl = null;
      if (source != null && !source.trim().isEmpty()) {
        loadLocalImage();
      } else {
        Log.w("ARImageView", "setImageSource called with null or empty source");
      }
    }

    public void setImageUrl(String url) {
      this.imageUrl = url;
      this.imageSource = null;
      if (url != null && !url.trim().isEmpty()) {
        loadRemoteImage();
      } else {
        Log.w("ARImageView", "setImageUrl called with null or empty URL");
      }
    }

  // In the loadLocalImage() method, add file URI handling:

    private void loadLocalImage() {
  // Add comprehensive null/empty checks
  if (imageSource == null || imageSource.isEmpty() || imageSource.trim().isEmpty()) {
    Log.w("ARImageView", "Image source is null or empty");
    emitAREvent("IMAGE_ERROR", "No image source provided");
    return;
  }
  
  // Capture the value in a local final variable to avoid race conditions
  final String sourceToLoad = imageSource.trim();
  
  emitAREvent("IMAGE_LOADING", "Loading image from local source");
  Log.d("ARImageView", "Loading image from source: " + sourceToLoad);
  
  queueEvent(() -> {
    try {
      Bitmap bitmap = null;
      String source = sourceToLoad; // Use captured value
      
      // Check if it's a file:// URI (from gallery)
      if (source.startsWith("file://")) {
        String filePath = source.replace("file://", "");
        Log.d("ARImageView", "Loading image from file path: " + filePath);
        bitmap = BitmapFactory.decodeFile(filePath);
        
        if (bitmap == null) {
          Log.e("ARImageView", "Failed to decode file: " + filePath);
        } else {
          Log.d("ARImageView", "Successfully loaded image from file: " + 
                bitmap.getWidth() + "x" + bitmap.getHeight());
        }
      } 
      // Check if it's a content:// URI (from gallery on newer Android)
      else if (source.startsWith("content://")) {
        try {
          android.net.Uri uri = android.net.Uri.parse(source);
          InputStream inputStream = getContext().getContentResolver().openInputStream(uri);
          
          if (inputStream != null) {
            bitmap = BitmapFactory.decodeStream(inputStream);
            inputStream.close();
            Log.d("ARImageView", "Loading image from content URI: " + source);
            
            if (bitmap == null) {
              Log.e("ARImageView", "Failed to decode stream from content URI: " + source);
            } else {
              Log.d("ARImageView", "Successfully loaded image from content URI: " + 
                    bitmap.getWidth() + "x" + bitmap.getHeight());
            }
          } else {
            Log.e("ARImageView", "Could not open input stream for URI: " + source);
          }
        } catch (Exception e) {
          Log.e("ARImageView", "Error loading from content URI: " + source, e);
          e.printStackTrace();
        }
      }
      // Check if it's an absolute file path (without file:// prefix)
      else if (source.startsWith("/")) {
        Log.d("ARImageView", "Loading image from absolute path: " + source);
        bitmap = BitmapFactory.decodeFile(source);
        
        if (bitmap == null) {
          Log.e("ARImageView", "Failed to decode file from absolute path: " + source);
        } else {
          Log.d("ARImageView", "Successfully loaded image from absolute path: " + 
                bitmap.getWidth() + "x" + bitmap.getHeight());
        }
      }
      // Try as drawable resource
      else {
        String resourceName = source
            .replace(".jpeg", "")
            .replace(".jpg", "")
            .replace(".png", "")
            .replace(".webp", "");
        
        int resourceId = getContext().getResources().getIdentifier(
            resourceName,
            "drawable",
            getContext().getPackageName()
        );
        
        if (resourceId != 0) {
          Log.d("ARImageView", "Loading image from drawable resource: " + resourceName);
          bitmap = BitmapFactory.decodeResource(getContext().getResources(), resourceId);
          
          if (bitmap != null) {
            Log.d("ARImageView", "Successfully loaded drawable: " + 
                  bitmap.getWidth() + "x" + bitmap.getHeight());
          }
        } else {
          // Try loading from assets
          try {
            Log.d("ARImageView", "Attempting to load from assets: " + source);
            InputStream is = getContext().getAssets().open(source);
            bitmap = BitmapFactory.decodeStream(is);
            is.close();
            
            if (bitmap != null) {
              Log.d("ARImageView", "Successfully loaded from assets: " + 
                    bitmap.getWidth() + "x" + bitmap.getHeight());
            }
          } catch (IOException e) {
            Log.e("ARImageView", "Error loading from assets: " + source, e);
          }
        }
      }
      
      if (bitmap != null) {
        currentBitmap = bitmap;
        Log.d("ARImageView", "Bitmap decoded successfully: " +
              currentBitmap.getWidth() + "x" + currentBitmap.getHeight());
        
        if (imageRenderer != null) {
          imageRenderer.updateBitmap(currentBitmap);
          imageLoaded = true;
          emitAREvent("IMAGE_LOADED", "Image loaded successfully");
          Log.d("ARImageView", "Image loaded into renderer successfully");
        } else {
          // Renderer not ready yet - bitmap will be loaded in onSurfaceCreated
          Log.d("ARImageView", "Bitmap ready, waiting for renderer initialization");
          emitAREvent("IMAGE_LOADING", "Image decoded, waiting for AR surface");
        }
      } else {
        emitAREvent("IMAGE_ERROR", "Failed to decode image from source");
        Log.e("ARImageView", "Failed to load bitmap from any source method. Source was: " + source);
      }
    } catch (Exception e) {
      e.printStackTrace();
      emitAREvent("IMAGE_ERROR", "Error loading image: " + e.getMessage());
      Log.e("ARImageView", "Exception while loading image: " + e.getMessage(), e);
    }
  });
}

    private void loadRemoteImage() {
      if (imageUrl == null || imageUrl.isEmpty()) {
        Log.w("ARImageView", "Image URL is null or empty");
        emitAREvent("IMAGE_ERROR", "No image URL provided");
        return;
      }
      
      emitAREvent("IMAGE_LOADING", "Downloading image from URL");
      Log.d("ARImageView", "Starting download from URL: " + imageUrl);
      
      // Use thread instead of deprecated AsyncTask
      new Thread(() -> {
        Bitmap bitmap = null;
        String errorMessage = "Unknown error";
        
        try {
          URL url = new URL(imageUrl);
          Log.d("ARImageView", "Opening connection to: " + url.toString());
          
          HttpURLConnection connection = (HttpURLConnection) url.openConnection();
          connection.setDoInput(true);
          connection.setConnectTimeout(10000); // 10 seconds timeout
          connection.setReadTimeout(10000); // 10 seconds timeout
          connection.setRequestMethod("GET");
          
          // Set user agent to avoid some servers blocking requests
          connection.setRequestProperty("User-Agent", "Mozilla/5.0");
          
          int responseCode = connection.getResponseCode();
          Log.d("ARImageView", "HTTP Response Code: " + responseCode);
          
          if (responseCode == HttpURLConnection.HTTP_OK) {
            InputStream input = connection.getInputStream();
            bitmap = BitmapFactory.decodeStream(input);
            input.close();
            
            if (bitmap != null) {
              Log.d("ARImageView", "Image downloaded successfully: " + 
                    bitmap.getWidth() + "x" + bitmap.getHeight());
            } else {
              errorMessage = "Failed to decode image stream";
              Log.e("ARImageView", errorMessage);
            }
          } else {
            errorMessage = "HTTP error code: " + responseCode;
            Log.e("ARImageView", errorMessage);
          }
          
          connection.disconnect();
        } catch (java.net.MalformedURLException e) {
          errorMessage = "Invalid URL: " + e.getMessage();
          Log.e("ARImageView", errorMessage, e);
          e.printStackTrace();
        } catch (java.net.SocketTimeoutException e) {
          errorMessage = "Connection timeout: " + e.getMessage();
          Log.e("ARImageView", errorMessage, e);
          e.printStackTrace();
        } catch (java.io.IOException e) {
          errorMessage = "Network error: " + e.getMessage();
          Log.e("ARImageView", errorMessage, e);
          e.printStackTrace();
        } catch (Exception e) {
          errorMessage = "Download failed: " + e.getMessage();
          Log.e("ARImageView", errorMessage, e);
          e.printStackTrace();
        }
        
        final Bitmap finalBitmap = bitmap;
        final String finalError = errorMessage;
        
        // Post result back to UI thread
        if (finalBitmap != null) {
          currentBitmap = finalBitmap;
          Log.d("ARImageView", "Image downloaded successfully: " + 
                finalBitmap.getWidth() + "x" + finalBitmap.getHeight());
          
          queueEvent(() -> {
            if (imageRenderer != null) {
              imageRenderer.updateBitmap(currentBitmap);
              imageLoaded = true;
              emitAREvent("IMAGE_LOADED", "Image downloaded and loaded");
              Log.d("ARImageView", "Image loaded into renderer successfully");
            } else {
              // Renderer not ready yet - bitmap will be loaded in onSurfaceCreated
              Log.d("ARImageView", "Bitmap ready, waiting for renderer initialization");
              emitAREvent("IMAGE_LOADING", "Image downloaded, waiting for AR surface");
            }
          });
        } else {
          emitAREvent("IMAGE_ERROR", "Failed to download image: " + finalError);
          Log.e("ARImageView", "Failed to load image from URL: " + finalError);
        }
      }).start();
    }

    private float getRotationAngle(MotionEvent event) {
      if (event.getPointerCount() < 2) return 0f;
      float deltaX = event.getX(1) - event.getX(0);
      float deltaY = event.getY(1) - event.getY(0);
      return (float) Math.toDegrees(Math.atan2(deltaY, deltaX));
    }

    private void handleTapOnGlThread(Frame frame) {
      if (frame == null) return;
      List<HitResult> hitResults = frame.hitTest(pendingTapX, pendingTapY);
      for (HitResult hit : hitResults) {
        Trackable trackable = hit.getTrackable();
        if (trackable instanceof Plane && ((Plane) trackable).isPoseInPolygon(hit.getHitPose())) {
          Anchor anchor = hit.createAnchor();
          anchors.add(anchor);
          anchorRotations.add(new float[]{0f, 0f, 0f});
          currentRotationX = 0f;
          currentRotationY = 0f;
          currentRotationZ = 0f;
          break;
        } else if (trackable instanceof Point && ((Point) trackable).getOrientationMode() == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL) {
          Anchor anchor = hit.createAnchor();
          anchors.add(anchor);
          anchorRotations.add(new float[]{0f, 0f, 0f});
          currentRotationX = 0f;
          currentRotationY = 0f;
          currentRotationZ = 0f;
          break;
        }
      }
    }

    private void handleDragOnGlThread(Frame frame) {
      if (frame == null || anchors.isEmpty()) return;
      List<HitResult> hitResults = frame.hitTest(dragX, dragY);
      for (HitResult hit : hitResults) {
        Trackable trackable = hit.getTrackable();
        if ((trackable instanceof Plane && ((Plane) trackable).isPoseInPolygon(hit.getHitPose()))
            || (trackable instanceof Point && ((Point) trackable).getOrientationMode() == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL)) {
          int lastIndex = anchors.size() - 1;
          Anchor old = anchors.get(lastIndex);
          try { old.detach(); } catch (Exception ignored) {}
          Anchor anchor = hit.createAnchor();
          anchors.set(lastIndex, anchor);
          if (lastIndex < anchorRotations.size()) {
            anchorRotations.set(lastIndex, new float[]{currentRotationX, currentRotationY, currentRotationZ});
          }
          break;
        }
      }
    }

    private void handleRotationOnGlThread() {
      if (anchors.isEmpty()) return;
      int lastIndex = anchors.size() - 1;
      if (lastIndex < anchorRotations.size()) {
        anchorRotations.set(lastIndex, new float[]{currentRotationX, currentRotationY, currentRotationZ});
      }
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
      GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f);
      
      if (initializationError != null) {
        GLES20.glClearColor(0.2f, 0.0f, 0.0f, 1.0f);
        return;
      }
      
      int[] textures = new int[1];
      GLES20.glGenTextures(1, textures, 0);
      cameraTextureId = textures[0];
      GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId);
      GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
      GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
      GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
      GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
      
      backgroundRenderer = new BackgroundRenderer();
      imageRenderer = new ImageBillboardRenderer();
      planeRenderer = new PlaneRenderer();
      
      Log.d("ARImageView", "Renderers initialized");
      
      if (arSession != null && sessionInitialized) {
        arSession.setCameraTextureName(cameraTextureId);
      }
      
      // Check if bitmap was loaded before renderer was ready
      if (currentBitmap != null) {
        Log.d("ARImageView", "Applying pre-loaded bitmap to renderer");
        imageRenderer.updateBitmap(currentBitmap);
        imageLoaded = true;
        emitAREvent("IMAGE_LOADED", "Image loaded successfully");
      }
      // Otherwise, load image if source is set
      else if (imageSource != null) {
        Log.d("ARImageView", "Loading image from source in onSurfaceCreated");
        loadLocalImage();
      } else if (imageUrl != null) {
        Log.d("ARImageView", "Loading image from URL in onSurfaceCreated");
        loadRemoteImage();
      }
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
      GLES20.glViewport(0, 0, width, height);
      viewportWidth = width;
      viewportHeight = height;
      if (arSession != null) {
        arSession.setDisplayGeometry(getSurfaceRotation(), width, height);
      }
    }

    @Override
    public void onDrawFrame(GL10 gl) {
      GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
      
      if (arSession == null || !sessionInitialized || initializationError != null || cameraTextureId == -1) {
        return;
      }
      
      try {
        Frame frame = arSession.update();
        Camera camera = frame.getCamera();
        arSession.setDisplayGeometry(getSurfaceRotation(), viewportWidth, viewportHeight);

        // Check for plane detection
        boolean planesFoundThisFrame = false;
        for (Plane plane : frame.getUpdatedTrackables(Plane.class)) {
          if (plane.getTrackingState() == TrackingState.TRACKING) {
            planesFoundThisFrame = true;
            if (!hasEmittedPlaneDetection) {
              planeDetected = true;
              hasEmittedPlaneDetection = true;
              emitAREvent("PLANE_DETECTED", "Surface detected - tap to place image");
            }
            break;
          }
        }

        // Handle touch interactions
        if (pendingTap && camera.getTrackingState() == TrackingState.TRACKING) {
          if (!imageLoaded) {
            emitAREvent("PLACEMENT_BLOCKED", "Image still loading, please wait");
          } else if (!planeDetected) {
            emitAREvent("PLACEMENT_BLOCKED", "No surface detected, keep scanning");
          } else {
            handleTapOnGlThread(frame);
            emitAREvent("IMAGE_PLACED", "Image placed successfully");
          }
          pendingTap = false;
        }
        
        if (dragging && camera.getTrackingState() == TrackingState.TRACKING) {
          handleDragOnGlThread(frame);
        }
        if (rotating && camera.getTrackingState() == TrackingState.TRACKING) {
          handleRotationOnGlThread();
        }
        
        // Render camera background
        if (backgroundRenderer != null && cameraTextureId != -1) {
          backgroundRenderer.draw(frame, cameraTextureId);
        }
        
        if (camera.getTrackingState() == TrackingState.TRACKING) {
          float[] proj = new float[16];
          float[] view = new float[16];
          camera.getProjectionMatrix(proj, 0, 0.1f, 100.0f);
          camera.getViewMatrix(view, 0);

          // Draw detected planes (only before first placement)
          if (planeRenderer != null && anchors.isEmpty()) {
            for (Plane plane : frame.getUpdatedTrackables(Plane.class)) {
              if (plane.getTrackingState() == TrackingState.TRACKING && plane.getSubsumedBy() == null) {
                planeRenderer.drawPlane(plane, view, proj);
              }
            }
          }

          // Draw placed images
          for (int i = 0; i < anchors.size(); i++) {
            Anchor anchor = anchors.get(i);
            if (anchor.getTrackingState() != TrackingState.TRACKING) continue;
            float[] model = new float[16];
            anchor.getPose().toMatrix(model, 0);
            float[] rotations = i < anchorRotations.size() ? anchorRotations.get(i) : new float[]{0f, 0f, 0f};
            if (imageRenderer != null && currentBitmap != null) {
              imageRenderer.drawBillboard(model, view, proj, rotations);
            }
          }
        }
      } catch (CameraNotAvailableException e) {
        e.printStackTrace();
      } catch (IllegalStateException e) {
        e.printStackTrace();
      } catch (Exception e) {
        e.printStackTrace();
      }
    }

    public void onPause() {
      if (arSession != null && sessionInitialized) {
        try {
          arSession.pause();
        } catch (Exception e) {
          e.printStackTrace();
        }
      }
    }

    public void onResume() {
      try {
        initializationError = null;
        emitAREvent("AR_INITIALIZING", "Initializing AR session");
        
        if (arSession == null) {
          Activity activity = null;
          Context ctx = getContext();
          if (ctx instanceof ThemedReactContext) {
            Activity current = ((ThemedReactContext) ctx).getCurrentActivity();
            if (current != null) activity = current;
          }

          ArCoreApk.InstallStatus installStatus = ArCoreApk.getInstance().requestInstall(activity, !installRequested);
          if (installStatus == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
            installRequested = true;
            return;
          }

          arSession = new Session(getContext());
          Config config = new Config(arSession);
          config.setPlaneFindingMode(Config.PlaneFindingMode.HORIZONTAL);
          config.setFocusMode(Config.FocusMode.AUTO);
          
          if (arSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
            config.setDepthMode(Config.DepthMode.AUTOMATIC);
          }
          
          arSession.configure(config);
          sessionInitialized = true;
          arSessionReady = true;

          emitAREvent("AR_SESSION_READY", "AR session initialized - move phone to detect surfaces");

          if (cameraTextureId != -1) {
            arSession.setCameraTextureName(cameraTextureId);
          }

          if (viewportWidth > 0 && viewportHeight > 0) {
            arSession.setDisplayGeometry(getSurfaceRotation(), viewportWidth, viewportHeight);
          }
        }

        if (arSession != null) {
          arSession.resume();
        }
      } catch (UnavailableException | CameraNotAvailableException e) {
        initializationError = e;
        e.printStackTrace();
        emitAREvent("AR_ERROR", "Failed to initialize AR: " + e.getMessage());
      } catch (Exception e) {
        initializationError = e;
        e.printStackTrace();
        emitAREvent("AR_ERROR", "Failed to initialize AR: " + e.getMessage());
      }
    }

    private int getSurfaceRotation() {
      WindowManager wm = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
      if (wm == null) return Surface.ROTATION_0;
      Display display = wm.getDefaultDisplay();
      if (display == null) return Surface.ROTATION_0;
      return display.getRotation();
    }
  }

  // Background renderer
  private static class BackgroundRenderer {
    private static final String VERTEX_SHADER =
        "attribute vec4 a_Position;\n"
        + "attribute vec2 a_TexCoord;\n"
        + "varying vec2 v_TexCoord;\n"
        + "void main() {\n"
        + "   gl_Position = a_Position;\n"
        + "   v_TexCoord = a_TexCoord;\n"
        + "}";

    private static final String FRAGMENT_SHADER =
        "#extension GL_OES_EGL_image_external : require\n"
        + "precision highp float;\n"
        + "varying vec2 v_TexCoord;\n"
        + "uniform samplerExternalOES u_Texture;\n"
        + "void main() {\n"
        + "   gl_FragColor = texture2D(u_Texture, v_TexCoord);\n"
        + "}";

    private int program;
    private int positionAttrib;
    private int texCoordAttrib;
    private int textureUniform;
    private FloatBuffer quadVertices;
    private FloatBuffer quadTexCoordsView;
    private FloatBuffer quadTexCoordsTransformed;

    public BackgroundRenderer() {
      int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
      int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
      
      program = GLES20.glCreateProgram();
      GLES20.glAttachShader(program, vertexShader);
      GLES20.glAttachShader(program, fragmentShader);
      GLES20.glLinkProgram(program);
      
      positionAttrib = GLES20.glGetAttribLocation(program, "a_Position");
      texCoordAttrib = GLES20.glGetAttribLocation(program, "a_TexCoord");
      textureUniform = GLES20.glGetUniformLocation(program, "u_Texture");

      float[] vertices = {
          -1.0f, -1.0f,
          1.0f, -1.0f,
          -1.0f,  1.0f,
          1.0f,  1.0f,
      };
      
      float[] viewNormalizedCoords = {
          0.0f, 0.0f,
          1.0f, 0.0f,
          0.0f, 1.0f,
          1.0f, 1.0f,
      };

      quadVertices = ByteBuffer.allocateDirect(vertices.length * 4)
          .order(ByteOrder.nativeOrder()).asFloatBuffer();
      quadVertices.put(vertices).position(0);

      quadTexCoordsView = ByteBuffer.allocateDirect(viewNormalizedCoords.length * 4)
          .order(ByteOrder.nativeOrder()).asFloatBuffer();
      quadTexCoordsView.put(viewNormalizedCoords).position(0);

      quadTexCoordsTransformed = ByteBuffer.allocateDirect(viewNormalizedCoords.length * 4)
          .order(ByteOrder.nativeOrder()).asFloatBuffer();
      quadTexCoordsTransformed.put(viewNormalizedCoords).position(0);
    }

    private int loadShader(int type, String shaderCode) {
      int shader = GLES20.glCreateShader(type);
      GLES20.glShaderSource(shader, shaderCode);
      GLES20.glCompileShader(shader);
      return shader;
    }

    public void draw(Frame frame, int textureId) {
      if (frame.hasDisplayGeometryChanged()) {
        quadVertices.position(0);
        quadTexCoordsTransformed.position(0);
        frame.transformCoordinates2d(
            com.google.ar.core.Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
            quadVertices,
            com.google.ar.core.Coordinates2d.TEXTURE_NORMALIZED,
            quadTexCoordsTransformed);
        quadTexCoordsTransformed.position(0);
      }

      GLES20.glUseProgram(program);
      GLES20.glDepthMask(false);
      GLES20.glDisable(GLES20.GL_DEPTH_TEST);
      GLES20.glDisable(GLES20.GL_BLEND);

      quadVertices.position(0);
      quadTexCoordsTransformed.position(0);
      GLES20.glVertexAttribPointer(positionAttrib, 2, GLES20.GL_FLOAT, false, 0, quadVertices);
      GLES20.glVertexAttribPointer(texCoordAttrib, 2, GLES20.GL_FLOAT, false, 0, quadTexCoordsTransformed);
      
      GLES20.glEnableVertexAttribArray(positionAttrib);
      GLES20.glEnableVertexAttribArray(texCoordAttrib);

      GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
      GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
      GLES20.glUniform1i(textureUniform, 0);

      GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

      GLES20.glDisableVertexAttribArray(positionAttrib);
      GLES20.glDisableVertexAttribArray(texCoordAttrib);
      
      GLES20.glEnable(GLES20.GL_DEPTH_TEST);
      GLES20.glDepthMask(true);
    }
  }

  // Image billboard renderer
  private static class ImageBillboardRenderer {
    private static final String VERTEX_SHADER =
        "uniform mat4 u_MVP;\n" +
        "attribute vec4 a_Position;\n" +
        "attribute vec2 a_TexCoord;\n" +
        "varying vec2 v_TexCoord;\n" +
        "void main() {\n" +
        "  gl_Position = u_MVP * a_Position;\n" +
        "  v_TexCoord = a_TexCoord;\n" +
        "}";

    private static final String FRAGMENT_SHADER =
        "precision mediump float;\n" +
        "varying vec2 v_TexCoord;\n" +
        "uniform sampler2D u_Texture;\n" +
        "void main() {\n" +
        "  gl_FragColor = texture2D(u_Texture, v_TexCoord);\n" +
        "}";

    private int program;
    private int positionAttrib;
    private int texCoordAttrib;
    private int mvpUniform;
    private int textureUniform;

    private FloatBuffer quadVertices;
    private FloatBuffer quadTexCoords;
    private int imageTextureId = -1;
    private Bitmap currentBitmap = null;

    public ImageBillboardRenderer() {
      int v = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
      int f = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
      program = GLES20.glCreateProgram();
      GLES20.glAttachShader(program, v);
      GLES20.glAttachShader(program, f);
      GLES20.glLinkProgram(program);

      positionAttrib = GLES20.glGetAttribLocation(program, "a_Position");
      texCoordAttrib = GLES20.glGetAttribLocation(program, "a_TexCoord");
      mvpUniform = GLES20.glGetUniformLocation(program, "u_MVP");
      textureUniform = GLES20.glGetUniformLocation(program, "u_Texture");

      float size = 0.3f;
      float[] vertices = new float[] {
          -size, -size, 0f,
          size, -size, 0f,
          -size,  size, 0f,
          size,  size, 0f,
      };
      float[] uvs = new float[] {
          0f, 1f,
          1f, 1f,
          0f, 0f,
          1f, 0f,
      };
      quadVertices = ByteBuffer.allocateDirect(vertices.length * 4)
          .order(ByteOrder.nativeOrder()).asFloatBuffer();
      quadVertices.put(vertices).position(0);
      quadTexCoords = ByteBuffer.allocateDirect(uvs.length * 4)
          .order(ByteOrder.nativeOrder()).asFloatBuffer();
      quadTexCoords.put(uvs).position(0);
    }

    private int loadShader(int type, String code) {
      int shader = GLES20.glCreateShader(type);
      GLES20.glShaderSource(shader, code);
      GLES20.glCompileShader(shader);
      return shader;
    }

    public void updateBitmap(Bitmap bitmap) {
      if (bitmap == null) return;
      
      if (imageTextureId != -1) {
        int[] toDelete = new int[] { imageTextureId };
        GLES20.glDeleteTextures(1, toDelete, 0);
      }
      
      int[] textures = new int[1];
      GLES20.glGenTextures(1, textures, 0);
      imageTextureId = textures[0];
      GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, imageTextureId);
      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
      GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
      
      currentBitmap = bitmap;
    }

    public void drawBillboard(float[] model, float[] view, float[] proj, float[] rotations) {
      if (imageTextureId == -1 || currentBitmap == null) return;

      float[] translation = new float[16];
      Matrix.setIdentityM(translation, 0);
      translation[12] = model[12];
      translation[13] = model[13];
      translation[14] = model[14];

      float[] scaleMatrix = new float[16];
      Matrix.setIdentityM(scaleMatrix, 0);
      float aspectRatio = (float) currentBitmap.getWidth() / (float) currentBitmap.getHeight();
      float scale = 0.3f;
      Matrix.scaleM(scaleMatrix, 0, scale * aspectRatio, scale, scale);

      float rotationX = rotations.length > 0 ? rotations[0] : 0f;
      float rotationY = rotations.length > 1 ? rotations[1] : 0f;
      float rotationZ = rotations.length > 2 ? rotations[2] : 0f;
      
      float[] rotationMatrixX = new float[16];
      Matrix.setIdentityM(rotationMatrixX, 0);
      Matrix.rotateM(rotationMatrixX, 0, rotationX, 1, 0, 0);
      
      float[] rotationMatrixY = new float[16];
      Matrix.setIdentityM(rotationMatrixY, 0);
      Matrix.rotateM(rotationMatrixY, 0, rotationY, 0, 1, 0);
      
      float[] rotationMatrixZ = new float[16];
      Matrix.setIdentityM(rotationMatrixZ, 0);
      Matrix.rotateM(rotationMatrixZ, 0, rotationZ, 0, 0, 1);

      float[] rotationYX = new float[16];
      float[] rotationZYX = new float[16];
      Matrix.multiplyMM(rotationYX, 0, rotationMatrixY, 0, rotationMatrixX, 0);
      Matrix.multiplyMM(rotationZYX, 0, rotationMatrixZ, 0, rotationYX, 0);

      float[] tempModel = new float[16];
      float[] billboardModel = new float[16];
      Matrix.multiplyMM(tempModel, 0, rotationZYX, 0, scaleMatrix, 0);
      Matrix.multiplyMM(billboardModel, 0, translation, 0, tempModel, 0);

      float[] modelView = new float[16];
      float[] modelViewProj = new float[16];
      Matrix.multiplyMM(modelView, 0, view, 0, billboardModel, 0);
      Matrix.multiplyMM(modelViewProj, 0, proj, 0, modelView, 0);

      GLES20.glUseProgram(program);
      GLES20.glDisable(GLES20.GL_DEPTH_TEST);
      GLES20.glDepthMask(false);
      GLES20.glEnable(GLES20.GL_BLEND);
      GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);

      GLES20.glUniformMatrix4fv(mvpUniform, 1, false, modelViewProj, 0);

      quadVertices.position(0);
      quadTexCoords.position(0);
      GLES20.glVertexAttribPointer(positionAttrib, 3, GLES20.GL_FLOAT, false, 0, quadVertices);
      GLES20.glEnableVertexAttribArray(positionAttrib);
      GLES20.glVertexAttribPointer(texCoordAttrib, 2, GLES20.GL_FLOAT, false, 0, quadTexCoords);
      GLES20.glEnableVertexAttribArray(texCoordAttrib);

      GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
      GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, imageTextureId);
      GLES20.glUniform1i(textureUniform, 0);

      GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

      GLES20.glDisableVertexAttribArray(positionAttrib);
      GLES20.glDisableVertexAttribArray(texCoordAttrib);
      GLES20.glDisable(GLES20.GL_BLEND);
      GLES20.glDepthMask(true);
      GLES20.glEnable(GLES20.GL_DEPTH_TEST);
    }
  }

  // Plane renderer for visualization
  private static class PlaneRenderer {
    private static final String VERTEX_SHADER =
        "uniform mat4 u_MVP;\n" +
        "attribute vec4 a_Position;\n" +
        "void main() {\n" +
        "  gl_Position = u_MVP * a_Position;\n" +
        "  gl_PointSize = 8.0;\n" +
        "}";

    private static final String FRAGMENT_SHADER =
        "precision mediump float;\n" +
        "void main() {\n" +
        "  float dist = length(gl_PointCoord - vec2(0.5));\n" +
        "  if (dist > 0.5) discard;\n" +
        "  gl_FragColor = vec4(1.0, 1.0, 1.0, 0.8);\n" +
        "}";

    private int program;
    private int positionAttrib;
    private int mvpUniform;

    public PlaneRenderer() {
      int v = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
      int f = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
      program = GLES20.glCreateProgram();
      GLES20.glAttachShader(program, v);
      GLES20.glAttachShader(program, f);
      GLES20.glLinkProgram(program);

      positionAttrib = GLES20.glGetAttribLocation(program, "a_Position");
      mvpUniform = GLES20.glGetUniformLocation(program, "u_MVP");
    }

    private int loadShader(int type, String code) {
      int shader = GLES20.glCreateShader(type);
      GLES20.glShaderSource(shader, code);
      GLES20.glCompileShader(shader);
      return shader;
    }

    public void drawPlane(Plane plane, float[] view, float[] proj) {
      if (plane.getTrackingState() != TrackingState.TRACKING) return;
      
      FloatBuffer vertices = plane.getPolygon();
      if (vertices.remaining() < 3) return;

      float[] model = new float[16];
      plane.getCenterPose().toMatrix(model, 0);

      float[] modelView = new float[16];
      float[] modelViewProj = new float[16];
      Matrix.multiplyMM(modelView, 0, view, 0, model, 0);
      Matrix.multiplyMM(modelViewProj, 0, proj, 0, modelView, 0);

      GLES20.glUseProgram(program);
      GLES20.glEnable(GLES20.GL_BLEND);
      GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);

      GLES20.glUniformMatrix4fv(mvpUniform, 1, false, modelViewProj, 0);
      
      vertices.position(0);
      GLES20.glVertexAttribPointer(positionAttrib, 3, GLES20.GL_FLOAT, false, 0, vertices);
      GLES20.glEnableVertexAttribArray(positionAttrib);

      GLES20.glDrawArrays(GLES20.GL_POINTS, 0, vertices.remaining() / 3);

      GLES20.glDisableVertexAttribArray(positionAttrib);
      GLES20.glDisable(GLES20.GL_BLEND);
    }
  }
}
