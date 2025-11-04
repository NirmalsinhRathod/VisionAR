package com.visionar;

import android.content.Context;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.view.Display;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.WindowManager;
import androidx.annotation.NonNull;
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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class NativeARTextViewManager extends SimpleViewManager<GLSurfaceView> {
  public static final String REACT_CLASS = "NativeARText";

  @Override
  public String getName() {
    return REACT_CLASS;
  }

  @Override
  @NonNull
  protected GLSurfaceView createViewInstance(@NonNull ThemedReactContext reactContext) {
    ARTextView arTextView = new ARTextView(reactContext);
    arTextView.onResume();
    return arTextView;
  }

  @ReactProp(name = "text")
  public void setText(GLSurfaceView view, @Nullable String text) {
    if (view instanceof ARTextView) {
      ((ARTextView) view).setText(text);
    }
  }

  @Override
  public void onDropViewInstance(@NonNull GLSurfaceView view) {
    if (view instanceof ARTextView) {
      ((ARTextView) view).onPause();
    }
    super.onDropViewInstance(view);
  }

  private static class ARTextView extends GLSurfaceView implements GLSurfaceView.Renderer {
    private Session arSession;
    private String displayText = "Hello AR";
    private final List<Anchor> anchors = new ArrayList<>();
    private final List<Float> anchorRotations = new ArrayList<>();
    private boolean sessionInitialized = false;
    private Exception initializationError = null;
    private int cameraTextureId = -1;
    private BackgroundRenderer backgroundRenderer;
    private TextBillboardRenderer textRenderer;
    private PlaneRenderer planeRenderer;
    private boolean installRequested = false;
    private int viewportWidth = 0;
    private int viewportHeight = 0;
    private volatile boolean pendingTap = false;
    private volatile float pendingTapX = 0f;
    private volatile float pendingTapY = 0f;

    // Rotation gesture tracking
    private boolean isRotating = false;
    private float touchDownX = 0f;
    private float touchDownY = 0f;
    private float rotationStartX = 0f;
    private float currentRotationAngle = 0f;
    private int rotatingAnchorIndex = -1;
    private static final float MOVEMENT_THRESHOLD = 15f; // pixels - reduced for better responsiveness
    private static final float HIT_TEST_RADIUS = 150f; // pixels for text selection - increased for easier selection
    private static final float ROTATION_SENSITIVITY = 0.8f; // rotation speed multiplier
    
    // Camera matrices for hit testing
    // These matrices are updated in onDrawFrame (GL thread) and read in touch events (UI thread)
    // matricesInitialized ensures we don't use zero/invalid matrices before first frame renders
    private float[] currentViewMatrix = new float[16];
    private float[] currentProjectionMatrix = new float[16];
    private boolean matricesInitialized = false;

    // State tracking for loading feedback
    private boolean arSessionReady = false;
    private boolean textRendererReady = false;
    private boolean planeDetected = false;
    private boolean hasEmittedPlaneDetection = false;
    private ThemedReactContext reactContext;

    public ARTextView(Context context) {
      super(context);
      this.reactContext = (ThemedReactContext) context;
      
      // Initialize matrices with identity to prevent zero-matrix issues
      Matrix.setIdentityM(currentViewMatrix, 0);
      Matrix.setIdentityM(currentProjectionMatrix, 0);
      
      setEGLContextClientVersion(2);
      setEGLConfigChooser(8, 8, 8, 8, 16, 0);
      getHolder().setFormat(android.graphics.PixelFormat.TRANSLUCENT);
      setPreserveEGLContextOnPause(true);
      setRenderer(this);
      setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

      setOnTouchListener((v, event) -> {
        switch (event.getAction()) {
          case MotionEvent.ACTION_DOWN:
            // Record initial touch position
            touchDownX = event.getX();
            touchDownY = event.getY();
            
            // Find which text/anchor the user is touching
            if (!anchors.isEmpty() && matricesInitialized) {
              rotatingAnchorIndex = findClosestAnchor(touchDownX, touchDownY);
              if (rotatingAnchorIndex >= 0) {
                // User touched an existing text - prepare for rotation
                rotationStartX = touchDownX;
                currentRotationAngle = anchorRotations.get(rotatingAnchorIndex);
                emitAREvent("TEXT_SELECTED", "Text selected for rotation (Index: " + rotatingAnchorIndex + ")");
              }
            }
            break;

          case MotionEvent.ACTION_MOVE:
            // Only allow rotation if we have a selected anchor
            if (rotatingAnchorIndex >= 0 && rotatingAnchorIndex < anchorRotations.size()) {
              // Check if movement exceeds threshold
              float deltaX = event.getX() - touchDownX;
              float deltaY = event.getY() - touchDownY;
              float distance = (float) Math.sqrt(deltaX * deltaX + deltaY * deltaY);
              
              // If moved beyond threshold, enter rotation mode
              if (!isRotating && distance > MOVEMENT_THRESHOLD) {
                isRotating = true;
                emitAREvent("TEXT_ROTATING", "Rotating text #" + rotatingAnchorIndex);
              }
              
              // Apply rotation if in rotation mode
              if (isRotating) {
                float rotationDeltaX = event.getX() - rotationStartX;
                float rotationDelta = rotationDeltaX * ROTATION_SENSITIVITY;
                float newRotation = currentRotationAngle + rotationDelta;
                anchorRotations.set(rotatingAnchorIndex, newRotation);
              }
            }
            break;

          case MotionEvent.ACTION_UP:
            // Determine if this was a tap or a drag
            float upDeltaX = event.getX() - touchDownX;
            float upDeltaY = event.getY() - touchDownY;
            float upDistance = (float) Math.sqrt(upDeltaX * upDeltaX + upDeltaY * upDeltaY);
            
            if (!isRotating && upDistance <= MOVEMENT_THRESHOLD) {
              // This was a tap - place new text
              pendingTapX = event.getX();
              pendingTapY = event.getY();
              pendingTap = true;
            } else if (isRotating) {
              // Rotation completed
              emitAREvent("TEXT_UPDATED", "Text rotation completed");
            }
            
            // Reset rotation state
            isRotating = false;
            rotatingAnchorIndex = -1;
            break;

          case MotionEvent.ACTION_CANCEL:
            // Reset rotation state
            isRotating = false;
            rotatingAnchorIndex = -1;
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
        params.putBoolean("textRendererReady", textRendererReady);
        params.putBoolean("planeDetected", planeDetected);
        
        reactContext
          .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
          .emit("onARTextStateChange", params);
      }
    }

    // Find which anchor/text is closest to the touch point
    private int findClosestAnchor(float touchX, float touchY) {
      if (anchors.isEmpty() || viewportWidth == 0 || viewportHeight == 0) {
        return -1;
      }
      
      // Ensure matrices are initialized before performing hit tests
      if (!matricesInitialized) {
        return -1;
      }

      int closestIndex = -1;
      float closestDistance = Float.MAX_VALUE;

      // Check each anchor
      for (int i = 0; i < anchors.size(); i++) {
        Anchor anchor = anchors.get(i);
        if (anchor.getTrackingState() != TrackingState.TRACKING) continue;

        // Get anchor position in 3D world space
        float[] anchorMatrix = new float[16];
        anchor.getPose().toMatrix(anchorMatrix, 0);
        
        // Extract world position
        float[] worldPos = new float[4];
        worldPos[0] = anchorMatrix[12];
        worldPos[1] = anchorMatrix[13];
        worldPos[2] = anchorMatrix[14];
        worldPos[3] = 1.0f;

        // Transform to clip space: projection * view * world
        float[] viewPos = new float[4];
        Matrix.multiplyMV(viewPos, 0, currentViewMatrix, 0, worldPos, 0);
        
        float[] clipPos = new float[4];
        Matrix.multiplyMV(clipPos, 0, currentProjectionMatrix, 0, viewPos, 0);

        // Perspective divide to get normalized device coordinates
        // Skip if behind camera or w is too small
        if (clipPos[3] <= 0.0001f) continue; // Behind camera or invalid
        
        float ndcX = clipPos[0] / clipPos[3];
        float ndcY = clipPos[1] / clipPos[3];
        
        // Skip if outside normalized device coordinates range
        if (Math.abs(ndcX) > 2.0f || Math.abs(ndcY) > 2.0f) continue;

        // Convert to screen coordinates
        float screenX = (ndcX + 1.0f) * 0.5f * viewportWidth;
        float screenY = (1.0f - ndcY) * 0.5f * viewportHeight; // Flip Y

        // Calculate distance from touch point
        float dx = screenX - touchX;
        float dy = screenY - touchY;
        float distance = (float) Math.sqrt(dx * dx + dy * dy);

        // Check if this is the closest anchor within hit radius
        if (distance < closestDistance && distance < HIT_TEST_RADIUS) {
          closestDistance = distance;
          closestIndex = i;
        }
      }

      return closestIndex;
    }

    public void setText(String text) {
      this.displayText = text;
      queueEvent(() -> {
        if (textRenderer != null) {
          emitAREvent("TEXT_UPDATING", "Updating text content");
          textRenderer.updateText(displayText);
          emitAREvent("TEXT_UPDATED", "Text content updated");
        }
      });
    }

    private void handleTapOnGlThread(Frame frame) {
      if (frame == null) return;
      List<HitResult> hitResults = frame.hitTest(pendingTapX, pendingTapY);
      for (HitResult hit : hitResults) {
        Trackable trackable = hit.getTrackable();
        if (trackable instanceof Plane && ((Plane) trackable).isPoseInPolygon(hit.getHitPose())) {
          Anchor anchor = hit.createAnchor();
          anchors.add(anchor);
          anchorRotations.add(0f); // Initialize with 0 rotation
          break;
        } else if (trackable instanceof Point && ((Point) trackable).getOrientationMode() == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL) {
          Anchor anchor = hit.createAnchor();
          anchors.add(anchor);
          anchorRotations.add(0f); // Initialize with 0 rotation
          break;
        }
      }
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
      GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f);
      
      if (initializationError != null) {
        GLES20.glClearColor(0.2f, 0.0f, 0.0f, 1.0f);
        return;
      }
      
      emitAREvent("RENDERER_INITIALIZING", "Setting up AR renderer");
      
      int[] textures = new int[1];
      GLES20.glGenTextures(1, textures, 0);
      cameraTextureId = textures[0];
      GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId);
      GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
      GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
      GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
      GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
      
      backgroundRenderer = new BackgroundRenderer();
      textRenderer = new TextBillboardRenderer();
      planeRenderer = new PlaneRenderer();
      
      if (arSession != null && sessionInitialized) {
        arSession.setCameraTextureName(cameraTextureId);
      }
      
      // Initialize text texture
      textRenderer.updateText(displayText);
      textRendererReady = true;
      emitAREvent("TEXT_RENDERER_READY", "Text renderer initialized");
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
        for (Plane plane : frame.getUpdatedTrackables(Plane.class)) {
          if (plane.getTrackingState() == TrackingState.TRACKING) {
            if (!hasEmittedPlaneDetection) {
              planeDetected = true;
              hasEmittedPlaneDetection = true;
              emitAREvent("PLANE_DETECTED", "Surface detected - tap to place text");
            }
            break;
          }
        }

        // Handle touch interactions
        if (pendingTap && camera.getTrackingState() == TrackingState.TRACKING) {
          if (!textRendererReady) {
            emitAREvent("PLACEMENT_BLOCKED", "Text renderer not ready, please wait");
          } else if (!planeDetected) {
            emitAREvent("PLACEMENT_BLOCKED", "No surface detected, keep scanning");
          } else {
            handleTapOnGlThread(frame);
            emitAREvent("TEXT_PLACED", "Text placed successfully");
          }
          pendingTap = false;
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

          // Store matrices for hit testing
          System.arraycopy(view, 0, currentViewMatrix, 0, 16);
          System.arraycopy(proj, 0, currentProjectionMatrix, 0, 16);
          matricesInitialized = true;

          // Draw detected planes (only before first placement)
          if (planeRenderer != null && anchors.isEmpty()) {
            for (Plane plane : frame.getUpdatedTrackables(Plane.class)) {
              if (plane.getTrackingState() == TrackingState.TRACKING && plane.getSubsumedBy() == null) {
                planeRenderer.drawPlane(plane, view, proj);
              }
            }
          }

          // Draw placed text
          for (int i = 0; i < anchors.size(); i++) {
            Anchor anchor = anchors.get(i);
            if (anchor.getTrackingState() != TrackingState.TRACKING) continue;
            
            // Ensure rotation data exists for this anchor
            if (i >= anchorRotations.size()) continue;
            
            float[] model = new float[16];
            anchor.getPose().toMatrix(model, 0);
            float rotation = anchorRotations.get(i);
            if (textRenderer != null) {
              textRenderer.drawBillboard(model, view, proj, rotation);
            }
          }
        }
      } catch (CameraNotAvailableException e) {
        e.printStackTrace();
      } catch (Exception e) {
        e.printStackTrace();
      }
    }

    public void onPause() {
      if (arSession != null && sessionInitialized) {
        try {
          arSession.pause();
          matricesInitialized = false; // Reset matrix state on pause
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

  // Background renderer (same as before)
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

      float[] vertices = {-1.0f, -1.0f, 1.0f, -1.0f, -1.0f, 1.0f, 1.0f, 1.0f};
      float[] viewNormalizedCoords = {0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f};

      quadVertices = ByteBuffer.allocateDirect(vertices.length * 4)
          .order(ByteOrder.nativeOrder()).asFloatBuffer();
      quadVertices.put(vertices).position(0);

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

  // Text billboard renderer
  private static class TextBillboardRenderer {
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
    private int textTextureId = -1;
    private Bitmap currentBitmap = null;

    public TextBillboardRenderer() {
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
      float[] vertices = {-size, -size, 0f, size, -size, 0f, -size, size, 0f, size, size, 0f};
      float[] uvs = {0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f};
      
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

    public void updateText(String text) {
      if (text == null || text.isEmpty()) text = "Hello AR";
      
      // Delete old texture
      if (textTextureId != -1) {
        int[] toDelete = {textTextureId};
        GLES20.glDeleteTextures(1, toDelete, 0);
      }
      
      // Create bitmap from text
      Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
      paint.setTextSize(120);
      paint.setColor(Color.WHITE);
      paint.setTypeface(Typeface.DEFAULT_BOLD);
      paint.setTextAlign(Paint.Align.CENTER);
      paint.setShadowLayer(10f, 0f, 0f, Color.BLACK);
      
      float textWidth = paint.measureText(text);
      int width = (int) Math.max(512, textWidth + 80);
      int height = 256;
      
      currentBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
      Canvas canvas = new Canvas(currentBitmap);
      canvas.drawColor(Color.TRANSPARENT);
      canvas.drawText(text, width / 2f, height / 2f + 40, paint);
      
      // Create OpenGL texture
      int[] textures = new int[1];
      GLES20.glGenTextures(1, textures, 0);
      textTextureId = textures[0];
      GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textTextureId);
      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
      GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, currentBitmap, 0);
    }

    public void drawBillboard(float[] model, float[] view, float[] proj, float rotationDegrees) {
      if (textTextureId == -1 || currentBitmap == null) return;

      float[] translation = new float[16];
      Matrix.setIdentityM(translation, 0);
      translation[12] = model[12];
      translation[13] = model[13];
      translation[14] = model[14];

      // Apply rotation around Y-axis (vertical axis)
      float[] rotationMatrix = new float[16];
      Matrix.setIdentityM(rotationMatrix, 0);
      Matrix.rotateM(rotationMatrix, 0, rotationDegrees, 0, 1, 0);

      float[] scaleMatrix = new float[16];
      Matrix.setIdentityM(scaleMatrix, 0);
      float aspectRatio = (float) currentBitmap.getWidth() / (float) currentBitmap.getHeight();
      float scale = 0.3f;
      Matrix.scaleM(scaleMatrix, 0, scale * aspectRatio, scale, scale);

      // Combine transformations: translation * rotation * scale
      float[] tempMatrix = new float[16];
      Matrix.multiplyMM(tempMatrix, 0, translation, 0, rotationMatrix, 0);
      
      float[] billboardModel = new float[16];
      Matrix.multiplyMM(billboardModel, 0, tempMatrix, 0, scaleMatrix, 0);

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
      GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textTextureId);
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
