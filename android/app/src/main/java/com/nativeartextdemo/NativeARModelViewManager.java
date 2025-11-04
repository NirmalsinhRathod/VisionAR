package com.visionar;

import android.content.Context;
import android.app.Activity;
import android.net.Uri;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.WindowManager;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.facebook.react.uimanager.SimpleViewManager;
import com.facebook.react.uimanager.ThemedReactContext;
import com.facebook.react.uimanager.annotations.ReactProp;

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

import com.google.ar.sceneform.rendering.ModelRenderable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class NativeARModelViewManager extends SimpleViewManager<GLSurfaceView> {
    public static final String REACT_CLASS = "NativeARModel";
    private static final String TAG = "ARModelView";

    @Override
    public String getName() {
        return REACT_CLASS;
    }

    @Override
    @NonNull
    protected GLSurfaceView createViewInstance(@NonNull ThemedReactContext reactContext) {
        ARModelView arModelView = new ARModelView(reactContext);
        arModelView.onResume();
        return arModelView;
    }

    @ReactProp(name = "modelSource")
    public void setModelSource(GLSurfaceView view, @Nullable String modelSource) {
        if (view instanceof ARModelView) {
            ((ARModelView) view).setModelSource(modelSource);
        }
    }

    @ReactProp(name = "modelScale")
    public void setModelScale(GLSurfaceView view, float scale) {
        if (view instanceof ARModelView) {
            ((ARModelView) view).setModelScale(scale);
        }
    }

    @Override
    public void onDropViewInstance(@NonNull GLSurfaceView view) {
        if (view instanceof ARModelView) {
            ((ARModelView) view).onPause();
        }
        super.onDropViewInstance(view);
    }

    private static class ARModelView extends GLSurfaceView implements GLSurfaceView.Renderer {
        private Session arSession;
        private String modelSource = null;
        private float modelScale = 1.0f;
        private ModelRenderable currentModel = null;
        private final List<Anchor> anchors = new ArrayList<>();
        private final List<float[]> anchorRotations = new ArrayList<>();
        private final List<Float> anchorScales = new ArrayList<>();
        private boolean sessionInitialized = false;
        private Exception initializationError = null;
        private int cameraTextureId = -1;
        private BackgroundRenderer backgroundRenderer;
        private boolean installRequested = false;
        private int viewportWidth = 0;
        private int viewportHeight = 0;
        
        // UI Thread Handler - CRITICAL FIX
        private final Handler uiHandler = new Handler(Looper.getMainLooper());
        
        // Touch handling
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

        public ARModelView(Context context) {
            super(context);
            setEGLContextClientVersion(2);
            setEGLConfigChooser(8, 8, 8, 8, 16, 0);
            getHolder().setFormat(android.graphics.PixelFormat.TRANSLUCENT);
            setPreserveEGLContextOnPause(true);
            setRenderer(this);
            setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

            setupTouchListener();
        }

        private void setupTouchListener() {
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

        public void setModelSource(String source) {
            this.modelSource = source;
            loadModel();
        }

        public void setModelScale(float scale) {
            this.modelScale = scale;
        }

        private void loadModel() {
            if (modelSource == null || modelSource.isEmpty()) return;
            
            // CRITICAL FIX: Load model on UI thread
            uiHandler.post(() -> {
                Context context = getContext();
                
                // Construct the URI for the model
                Uri modelUri;
                if (modelSource.startsWith("http://") || modelSource.startsWith("https://")) {
                    modelUri = Uri.parse(modelSource);
                } else {
                    // For local assets, use proper format
                    // If file is in assets/models/HORNET.glb, use: "models/HORNET.glb"
                    modelUri = Uri.parse(modelSource);
                }
                
                Log.d(TAG, "Loading model from: " + modelUri);
                
                // Build ModelRenderable on UI thread
                ModelRenderable.builder()
                    .setSource(context, modelUri)
                    .setIsFilamentGltf(true)
                    .build()
                    .thenAccept(renderable -> {
                        Log.d(TAG, "Model loaded successfully!");
                        currentModel = renderable;
                    })
                    .exceptionally(throwable -> {
                        Log.e(TAG, "Error loading model: " + throwable.getMessage(), throwable);
                        return null;
                    });
            });
        }

        private float getRotationAngle(MotionEvent event) {
            if (event.getPointerCount() < 2) return 0f;
            float deltaX = event.getX(1) - event.getX(0);
            float deltaY = event.getY(1) - event.getY(0);
            return (float) Math.toDegrees(Math.atan2(deltaY, deltaX));
        }

        private void handleTapOnGlThread(Frame frame) {
            if (frame == null || currentModel == null) return;
            List<HitResult> hitResults = frame.hitTest(pendingTapX, pendingTapY);
            for (HitResult hit : hitResults) {
                Trackable trackable = hit.getTrackable();
                if (trackable instanceof Plane && ((Plane) trackable).isPoseInPolygon(hit.getHitPose())) {
                    Anchor anchor = hit.createAnchor();
                    anchors.add(anchor);
                    anchorRotations.add(new float[]{0f, 0f, 0f});
                    anchorScales.add(modelScale);
                    currentRotationX = 0f;
                    currentRotationY = 0f;
                    currentRotationZ = 0f;
                    Log.d(TAG, "Model anchored at plane");
                    break;
                } else if (trackable instanceof Point && 
                          ((Point) trackable).getOrientationMode() == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL) {
                    Anchor anchor = hit.createAnchor();
                    anchors.add(anchor);
                    anchorRotations.add(new float[]{0f, 0f, 0f});
                    anchorScales.add(modelScale);
                    currentRotationX = 0f;
                    currentRotationY = 0f;
                    currentRotationZ = 0f;
                    Log.d(TAG, "Model anchored at point");
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
                    || (trackable instanceof Point && 
                       ((Point) trackable).getOrientationMode() == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL)) {
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
            
            if (arSession != null && sessionInitialized) {
                arSession.setCameraTextureName(cameraTextureId);
            }
            
            // Load model on UI thread after surface is ready
            if (modelSource != null) {
                loadModel();
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

                if (pendingTap && camera.getTrackingState() == TrackingState.TRACKING) {
                    handleTapOnGlThread(frame);
                    pendingTap = false;
                }
                if (dragging && camera.getTrackingState() == TrackingState.TRACKING) {
                    handleDragOnGlThread(frame);
                }
                if (rotating && camera.getTrackingState() == TrackingState.TRACKING) {
                    handleRotationOnGlThread();
                }
                
                if (backgroundRenderer != null && cameraTextureId != -1) {
                    backgroundRenderer.draw(frame, cameraTextureId);
                }
                
                // Note: Full Sceneform rendering integration requires more setup
                // This is a placeholder for where you'd render the model
                // For now, the model loads but full rendering needs Filament integration
                
            } catch (CameraNotAvailableException e) {
                Log.e(TAG, "Camera not available", e);
            } catch (Exception e) {
                Log.e(TAG, "Error in draw frame", e);
            }
        }

        public void onPause() {
            if (arSession != null && sessionInitialized) {
                try {
                    arSession.pause();
                } catch (Exception e) {
                    Log.e(TAG, "Error pausing session", e);
                }
            }
        }

        public void onResume() {
            try {
                initializationError = null;
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
                    config.setPlaneFindingMode(Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL);
                    config.setFocusMode(Config.FocusMode.AUTO);
                    
                    if (arSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                        config.setDepthMode(Config.DepthMode.AUTOMATIC);
                    }
                    
                    arSession.configure(config);
                    sessionInitialized = true;

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
                Log.e(TAG, "Error in onResume", e);
            } catch (Exception e) {
                initializationError = e;
                Log.e(TAG, "Error in onResume", e);
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

    // BackgroundRenderer class remains the same
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

            float[] vertices = {-1.0f, -1.0f, 1.0f, -1.0f, -1.0f,  1.0f, 1.0f,  1.0f};
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
}
