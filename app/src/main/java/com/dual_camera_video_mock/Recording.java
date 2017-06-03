package com.dual_camera_video_mock;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.media.AudioRecord;
import android.media.CamcorderProfile;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class Recording extends AppCompatActivity implements SurfaceHolder.Callback, SurfaceTexture.OnFrameAvailableListener, SensorEventListener {

    private int MY_PERMISSIONS_REQUEST_CAMERA=0;
    private int MY_PERMISSIONS_REQUEST_AUDIO=1;

    private AudioRecord audioRecord;
    private MediaCodec mediaCodec;
    CamcorderProfile camcorderProfile;
    final String VIDEO_MIME_TYPE = "video/avc";
    private MediaCodec videoCodec=null;
    int cameraId;
    volatile boolean isAudioAdded = false;
    volatile boolean isVideoAdded = false;
    Object recordSync = new Object();
    private static int VIDEO_WIDTH = 1280;  // dimensions for 720p video
    private static int VIDEO_HEIGHT = 720;
    private SurfaceView cameraView;
    final int FRAME_AVAILABLE = 1000;
    final int RECORD_STOP = 2000;
    final int RECORD_START = 3000;
    final int SAVE_VIDEO = 4000;
    //final int RECORD_COMPLETED = 4000;
    final int GET_RECORDER = 5000;
    final int SHUTDOWN = 6000;
    final int GET_READY = 7000;
    SurfaceTexture surfaceTexture;
    private Camera mCamera;
    private EGLDisplay mEGLDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext mEGLContext = EGL14.EGL_NO_CONTEXT;
    private EGLContext encoderEGLContext = EGL14.EGL_NO_CONTEXT;
    private EGLConfig mEGLConfig = null;
    // Android-specific extension.
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;
    public static final int FLAG_RECORDABLE = 0x01;
    private int mProgramHandle;
    private int mTextureTarget;
    private int mTextureId;
    private int muMVPMatrixLoc;
    private static final int SIZEOF_FLOAT = 4;
    int frameCount=0;
    private int muTexMatrixLoc;
    private int maPositionLoc;
    private int maTextureCoordLoc;
    /**
     * A "full" square, extending from -1 to +1 in both dimensions.  When the model/view/projection
     * matrix is identity, this will exactly cover the viewport.
     * <p>
     * The texture coordinates are Y-inverted relative to RECTANGLE.  (This seems to work out
     * right with external textures from SurfaceTexture.)
     */
    private static final float FULL_RECTANGLE_COORDS[] = {
            -1.0f, -1.0f,   // 0 bottom left
            1.0f, -1.0f,   // 1 bottom right
            -1.0f,  1.0f,   // 2 top left
            1.0f,  1.0f,   // 3 top right
    };
    private static final float FULL_RECTANGLE_TEX_COORDS[] = {
            0.0f, 0.0f,     // 0 bottom left
            1.0f, 0.0f,     // 1 bottom right
            0.0f, 1.0f,     // 2 top left
            1.0f, 1.0f      // 3 top right
    };
    //Surface onto which camera frames are drawn
    EGLSurface eglSurface;
    Surface videoSurface;
    //Surface to which camera frames are sent for encoding to mp4 format
    EGLSurface encoderSurface=null;
    private final float[] mTmpMatrix = new float[16];
    public static final float[] IDENTITY_MATRIX;
    static {
        IDENTITY_MATRIX = new float[16];
        Matrix.setIdentityM(IDENTITY_MATRIX, 0);
    }

    // Simple vertex shader, used for all programs.
    private static final String VERTEX_SHADER =
            "uniform mat4 uMVPMatrix;\n" +
                    "uniform mat4 uTexMatrix;\n" +
                    "attribute vec4 aPosition;\n" +
                    "attribute vec4 aTextureCoord;\n" +
                    "varying vec2 vTextureCoord;\n" +
                    "void main() {\n" +
                    "    gl_Position = uMVPMatrix * aPosition;\n" +
                    "    vTextureCoord = (uTexMatrix * aTextureCoord).xy;\n" +
                    "}\n";

    private static final String ENCODER_VERTEX_SHADER =
            "uniform mat4 enc_uMVPMatrix;\n" +
                    "uniform mat4 enc_uTexMatrix;\n" +
                    "attribute vec4 enc_aPosition;\n" +
                    "attribute vec4 enc_aTextureCoord;\n" +
                    "varying vec2 enc_vTextureCoord;\n" +
                    "void main() {\n" +
                    "    gl_Position = enc_uMVPMatrix * enc_aPosition;\n" +
                    "    vTextureCoord = (enc_uTexMatrix * enc_aTextureCoord).xy;\n" +
                    "}\n";

    // Simple fragment shader for use with external 2D textures (e.g. what we get from
    // SurfaceTexture).
    private static final String FRAGMENT_SHADER_EXT =
            "#extension GL_OES_EGL_image_external : require\n" +
                    "precision mediump float;\n" +
                    "varying vec2 vTextureCoord;\n" +
                    "uniform samplerExternalOES sTexture;\n" +
                    "void main() {\n" +
                    "    gl_FragColor = texture2D(sTexture, vTextureCoord);\n" +
                    "}\n";

    private static final String ENCODER_FRAGMENT_SHADER_EXT =
            "#extension GL_OES_EGL_image_external : require\n" +
                    "precision mediump float;\n" +
                    "varying vec2 vTextureCoord;\n" +
                    "uniform samplerExternalOES sTexture;\n" +
                    "void main() {\n" +
                    "    gl_FragColor = texture2D(sTexture, vTextureCoord);\n" +
                    "}\n";

    volatile boolean isRecording=false;
    volatile boolean isCapturing=false;
    final static String MIME_TYPE = "audio/mp4a-latm";
    final static int SAMPLE_RATE = 44100;
    final static int BIT_RATE = 128000;
    public static final int SAMPLES_PER_FRAME = 1024;	// AAC, bytes/frame/channel
    public static final int FRAMES_PER_BUFFER = 25; 	// AAC, frame/buffer/sec
    //MediaFormat audioFormat=null;
    MediaFormat videoFormat=null;
    int TIMEOUT = 10000;

    static final String AUDIO_PERMISSION = "android.permission.RECORD_AUDIO";
    static final String CAMERA_PERMISSION = "android.permission.CAMERA";
    final String TAG = this.getClass().getName();
    MediaMuxerHelper mediaMuxerHelper;
    MediaMuxer mediaMuxer;
    //RecordVideo.VideoRecordHandler recordHandler=null;
    Object renderObj = new Object();
    CameraRenderer.CameraHandler cameraHandler;
    VideoEncoder.VideoEncoderHandler videoEncoderHandler;
    //MainHandler mainHandler;
    volatile boolean isReady=false;
    boolean VERBOSE=false;
    //Thread audio;
    Thread video;
    /*private final SensorManager mSensorManager;
    private final Sensor mAccelerometer;*/

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if(requestCode == MY_PERMISSIONS_REQUEST_CAMERA){
            if(permissions!=null && permissions.length > 0){
                Log.d(TAG,"For camera");
                if(permissions[0].equalsIgnoreCase(CAMERA_PERMISSION)) {
                    if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                        SurfaceHolder sh = cameraView.getHolder();
                        sh.addCallback(this);
                        //setupCameraPreview();
                    } else {
                        Toast.makeText(getApplicationContext(), "Camera Permission not given. App cannot show Camera preview.", Toast.LENGTH_SHORT).show();
                    }
                }
            }
            else {
                Toast.makeText(getApplicationContext(), "Something wrong with obtaining Camera permissions. App cannot proceed with Camera preview", Toast.LENGTH_SHORT).show();
            }
        }
        else if(requestCode == MY_PERMISSIONS_REQUEST_AUDIO){
            if(permissions!=null && permissions.length > 0){
                Log.d(TAG,"For audio");
                if(!permissions[0].equalsIgnoreCase(AUDIO_PERMISSION)){
                    if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_DENIED) {
                        Toast.makeText(getApplicationContext(),"Audio Record Permission not given. App cannot record audio.",Toast.LENGTH_SHORT).show();
                    }
                }
            }
            else{
                Toast.makeText(getApplicationContext(),"Something wrong with obtaining Microphone permissions. App cannot proceed with Audio record.",Toast.LENGTH_SHORT).show();
            }
        }
    }

    /*public Recording(Sensor mAccelerometer, SensorManager mSensorManager) {
        this.mAccelerometer = mAccelerometer;
        this.mSensorManager = mSensorManager;
    }*/

    public Recording() {
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_recording);
        cameraView = (SurfaceView) findViewById(R.id.cameraView);
        checkForPermissions();
        final ImageButton recordButton = (ImageButton)findViewById(R.id.record_button);
        recordButton.setColorFilter(Color.DKGRAY);
        final Recording recording = this;
        recordButton.setOnClickListener(new View.OnClickListener(){
            @Override
            public void onClick(View view)
            {
                if(!isRecording){
                    recordButton.setColorFilter(Color.RED);
                    //Record here
                    //prepareMuxer();
                    //recordHandler.sendEmptyMessage(RECORD_START);
                    isRecording=true;
                    //mCamera.setDisplayOrientation(270);
                    /*Display display = ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay();
                    int orientation = display.getOrientation();
                    Log.d(TAG,"Orientation == "+orientation);*/
                    //if(orientation == Configuration.ORIENTATION_PORTRAIT) {
                        //setCameraDisplayOrientation(recording,cameraId,mCamera);
                    //}
                }
                else{
                    isRecording=false;
                    recordButton.setColorFilter(Color.DKGRAY);
                    //Stop here
                    videoEncoderHandler.sendEmptyMessage(RECORD_STOP);
                    Log.d(TAG,"Recorder thread EXITED...");
                }
            }
        });
        getSupportActionBar().hide();
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
    }

    @Override
    protected void onPause() {
        if(videoEncoderHandler!=null) {
            videoEncoderHandler.sendEmptyMessage(SHUTDOWN);
        }
        Log.d(TAG,"cameraHandler = "+cameraHandler);
        if(surfaceTexture!=null){
            surfaceTexture.release();
        }
        if(cameraHandler!=null) {
            cameraHandler.sendEmptyMessage(SHUTDOWN);
        }
        releaseCamera();
        //mSensorManager.unregisterListener(this);
        if(videoCodec!=null) {
            videoCodec.release();
            videoCodec = null;
        }
        releaseEGLSurface();
        releaseProgram();
        releaseEGLContext();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG,"Setting up camera");
        //checkForPermissions();
        //mSensorManager.registerListener(this, mAccelerometer, SensorManager.SENSOR_DELAY_NORMAL);
        setupCamera();
    }

    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    public void onSensorChanged(SensorEvent event) {
        Log.d(TAG,"Sensor changed == "+event.sensor);
        Log.d(TAG,"Sensor changed == "+event.values);
    }

    private void releaseCamera() {
        if (mCamera != null) {
            mCamera.stopPreview();
            mCamera.release();
            mCamera = null;
            Log.d(TAG, "releaseCamera -- done");
        }
    }

    private void releaseEGLSurface(){
        EGL14.eglDestroySurface(mEGLDisplay,eglSurface);
    }

    private void releaseProgram(){
        GLES20.glDeleteProgram(mProgramHandle);
    }

    private void releaseEGLContext()
    {
        if (mEGLDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(mEGLDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroyContext(mEGLDisplay, mEGLContext);
            EGL14.eglReleaseThread();
            EGL14.eglTerminate(mEGLDisplay);
        }

        mEGLDisplay = EGL14.EGL_NO_DISPLAY;
        mEGLContext = EGL14.EGL_NO_CONTEXT;
        mEGLConfig = null;
    }

    private void checkForPermissions() {
        int permission = ContextCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.CAMERA);
        if (permission == PackageManager.PERMISSION_GRANTED) {
            SurfaceHolder sh = cameraView.getHolder();
            sh.addCallback(this);
            //setupCameraPreview();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA},
                    MY_PERMISSIONS_REQUEST_CAMERA);
        }

        permission = ContextCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.RECORD_AUDIO);
        if (permission != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    MY_PERMISSIONS_REQUEST_AUDIO);
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder surfaceHolder) {
        Log.d(TAG, "surfCreated holder = " + surfaceHolder);
        prepareEGLDisplayandContext();
        CameraRenderer cameraRenderer = new CameraRenderer(surfaceHolder);
        cameraRenderer.start();
        waitUntilReady();
        VideoEncoder videoEncoder = new VideoEncoder();
        isReady=false;
        videoEncoder.start();
        waitUntilReady();
        videoEncoderHandler = videoEncoder.getHandler();
        Log.d(TAG,"Start Video encoder after EGL is setup");
        showPreview();
        surfaceTexture.setOnFrameAvailableListener(this);
    }

    @Override
    public void surfaceChanged(SurfaceHolder surfaceHolder, int format, int width, int height) {
        Log.d(TAG, "surfaceChanged fmt=" + format + " size=" + width + "x" + height +
                " holder=" + surfaceHolder);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder surfaceHolder) {
        Log.d(TAG, "surfaceDestroyed holder=" + surfaceHolder);
    }

    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        if(VERBOSE)Log.d(TAG,"FRAME Available now");
        cameraHandler.sendEmptyMessage(FRAME_AVAILABLE);
    }

    private void setupCamera()
    {
        Camera.CameraInfo info = new Camera.CameraInfo();
        for(int i=0;i<Camera.getNumberOfCameras();i++)
        {
            Camera.getCameraInfo(i, info);
            if(info.facing == Camera.CameraInfo.CAMERA_FACING_BACK){
                mCamera = Camera.open(i);
                cameraId = i;
                break;
            }
        }

        Camera.Parameters parameters = mCamera.getParameters();
        List<int[]> fps = parameters.getSupportedPreviewFpsRange();
        Iterator<int[]> iter = fps.iterator();
        //Safe to assume every camera would support 15 fps.
        int MIN_FPS = 15;
        int MAX_FPS = 15;
        while(iter.hasNext())
        {
            int[] frames = iter.next();
            if(!iter.hasNext())
            {
                MIN_FPS = frames[0];
                MAX_FPS = frames[1];
            }
        }
        Log.d(TAG,"Setting min and max Fps  == "+MIN_FPS+" , "+MAX_FPS);
        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(metrics);
        Log.d(TAG,"Width = "+metrics.widthPixels);
        Log.d(TAG,"Height = "+metrics.heightPixels);
        //Aspect ratio needs to be reversed, if orientation is portrait.
        Log.d(TAG,"SCREEN Aspect Ratio = "+(double)metrics.heightPixels/(double)metrics.widthPixels);
        double screenAspectRatio = (double)metrics.heightPixels/(double)metrics.widthPixels;
        //double screenAspectRatio = (double)metrics.widthPixels/(double)metrics.heightPixels;
        List<Camera.Size> previewSizes = parameters.getSupportedPreviewSizes();

        //If none of the camera preview size will (closely) match with screen resolution, default it to take the first preview size value.
        VIDEO_HEIGHT = previewSizes.get(0).height;
        VIDEO_WIDTH = previewSizes.get(0).width;
        for(int i = 0;i<previewSizes.size();i++)
        {
            double ar = (double)previewSizes.get(i).width/(double)previewSizes.get(i).height;
            Log.d(TAG,"Aspect ratio for "+previewSizes.get(i).width+" / "+previewSizes.get(i).height+" is = "+ar);
            if(Math.abs(screenAspectRatio - ar) <= 0.2){
                //Best match for camera preview!!
                VIDEO_HEIGHT = previewSizes.get(i).height;
                VIDEO_WIDTH = previewSizes.get(i).width;
                break;
            }
        }
        Log.d(TAG,"HEIGTH == "+VIDEO_HEIGHT+", WIDTH == "+VIDEO_WIDTH);
        double videoAspectRatio = (double)VIDEO_WIDTH/(double)VIDEO_HEIGHT;
        parameters.setPreviewSize(VIDEO_WIDTH,VIDEO_HEIGHT);
        parameters.setPreviewFpsRange(MIN_FPS,MAX_FPS);
        parameters.setRecordingHint(true);
        //parameters.setRotation(270);
        mCamera.setParameters(parameters);
        Log.d(TAG,"Orientation == "+info.orientation);
        //setCameraDisplayOrientation(this,cameraId,mCamera);
        //Log.d(TAG,"Orientation post change == "+info.orientation);
        // Set the preview aspect ratio.
        ViewGroup.LayoutParams layoutParams = cameraView.getLayoutParams();
        int temp = VIDEO_HEIGHT;
        VIDEO_HEIGHT = VIDEO_WIDTH;
        VIDEO_WIDTH = temp;
        /*layoutParams.height = VIDEO_WIDTH / (int)videoAspectRatio;
        layoutParams.width = (int)videoAspectRatio * VIDEO_HEIGHT;*/
        layoutParams.height = VIDEO_HEIGHT;
        layoutParams.width = VIDEO_WIDTH;
        Log.d(TAG,"LP Height = "+layoutParams.height);
        Log.d(TAG,"LP Width = "+layoutParams.width);
        temp = VIDEO_HEIGHT;
        VIDEO_HEIGHT = VIDEO_WIDTH;
        VIDEO_WIDTH = temp;
    }

    public void setCameraDisplayOrientation(Activity activity,
                                                   int cameraId, android.hardware.Camera camera) {
        android.hardware.Camera.CameraInfo info =
                new android.hardware.Camera.CameraInfo();
        android.hardware.Camera.getCameraInfo(cameraId, info);
        int rotation = activity.getWindowManager().getDefaultDisplay()
                .getRotation();
        int degrees = 0;
        Log.d(TAG,"Rotation = "+rotation);
        switch (rotation) {
            case Surface.ROTATION_0: degrees = 0; break;
            case Surface.ROTATION_90: degrees = 90; break;
            case Surface.ROTATION_180: degrees = 180; break;
            case Surface.ROTATION_270: degrees = 270; break;
        }

        int result;
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            result = (info.orientation + degrees) % 360;
            result = (360 - result) % 360;  // compensate the mirror
        } else {  // back-facing
            result = (info.orientation - degrees + 360) % 360;
        }
        Log.d(TAG,"Rotate by "+result);
        camera.setDisplayOrientation(result);
    }

    private void showPreview()
    {
        Log.d(TAG, "starting camera preview");
        try {
            mCamera.setPreviewTexture(surfaceTexture);
        } catch (IOException ioe) {
            throw new RuntimeException(ioe);
        }
        mCamera.startPreview();
    }

    class MainHandler extends Handler {
        WeakReference<Recording> recordVid;

        public MainHandler(Recording recordVideo) {
            recordVid = new WeakReference<>(recordVideo);
        }

        @Override
        public void handleMessage(Message msg) {

            Recording recording = recordVid.get();
            switch(msg.what)
            {
                case FRAME_AVAILABLE:
                    Log.d(TAG, "Calling drawFrame()....");
                    //audioVideoRecording.drawFrame();
                    Log.d(TAG, "drawFrame() done....");
                    break;
            }
        }
    }

    void waitUntilReady()
    {
        Log.d(TAG,"Waiting....");
        synchronized (renderObj)
        {
            while(!isReady){
                try {
                    renderObj.wait();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
        Log.d(TAG,"Come out of WAIT");
    }

    private void prepareEGLDisplayandContext()
    {
        mEGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (mEGLDisplay == EGL14.EGL_NO_DISPLAY) {
            throw new RuntimeException("unable to get EGL14 display");
        }
        int[] version = new int[2];
        if (!EGL14.eglInitialize(mEGLDisplay, version, 0, version, 1)) {
            mEGLDisplay = null;
            throw new RuntimeException("unable to initialize EGL14");
        }
        EGLConfig config = getConfig(FLAG_RECORDABLE, 2);
        if (config == null) {
            throw new RuntimeException("Unable to find a suitable EGLConfig");
        }
        int[] attrib2_list = {
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
        };
        EGLContext context = EGL14.eglCreateContext(mEGLDisplay, config, EGL14.EGL_NO_CONTEXT,
                attrib2_list, 0);
        checkEglError("eglCreateContext");
        mEGLConfig = config;
        mEGLContext = context;

        // Confirm with query.
        int[] values = new int[1];
        EGL14.eglQueryContext(mEGLDisplay, mEGLContext, EGL14.EGL_CONTEXT_CLIENT_VERSION,
                values, 0);
        Log.d(TAG, "EGLContext created, client version " + values[0]);
    }

    private void checkEglError(String msg) {
        int error;
        if ((error = EGL14.eglGetError()) != EGL14.EGL_SUCCESS) {
            throw new RuntimeException(msg + ": EGL error: 0x" + Integer.toHexString(error));
        }
    }

    private EGLConfig getConfig(int flags, int version) {
        int renderableType = EGL14.EGL_OPENGL_ES2_BIT;

        // The actual surface is generally RGBA or RGBX, so situationally omitting alpha
        // doesn't really help.  It can also lead to a huge performance hit on glReadPixels()
        // when reading into a GL_RGBA buffer.
        int[] attribList = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                //EGL14.EGL_DEPTH_SIZE, 16,
                //EGL14.EGL_STENCIL_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, renderableType,
                EGL14.EGL_NONE, 0,      // placeholder for recordable [@-3]
                EGL14.EGL_NONE
        };
        if ((flags & FLAG_RECORDABLE) != 0) {
            attribList[attribList.length - 3] = EGL_RECORDABLE_ANDROID;
            attribList[attribList.length - 2] = 1;
        }
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        if (!EGL14.eglChooseConfig(mEGLDisplay, attribList, 0, configs, 0, configs.length,
                numConfigs, 0)) {
            Log.w(TAG, "unable to find RGB8888 / " + version + " EGLConfig");
            return null;
        }
        return configs[0];
    }

    class CameraRenderer extends Thread
    {
        SurfaceHolder surfaceHolder;

        public CameraRenderer(SurfaceHolder holder)
        {
            surfaceHolder=holder;
        }

        @Override
        public void run()
        {
            Looper.prepare();
            cameraHandler = new CameraHandler(this);
            createSurfaceTexture();
            synchronized (renderObj){
                isReady=true;
                renderObj.notify();
            }
            if(VERBOSE)Log.d(TAG,"Main thread notified");
            Looper.loop();
            Log.d(TAG,"Camera Renderer STOPPED");
        }

        private void makeCurrent(EGLSurface surface)
        {
            EGL14.eglMakeCurrent(mEGLDisplay, surface, surface, mEGLContext);
        }
        /**
         * Creates a texture object suitable for use with this program.
         * <p>
         * On exit, the texture will be bound.
         */
        public int createGLTextureObject() {
            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            GlUtil.checkGlError("glGenTextures");

            int texId = textures[0];
            GLES20.glBindTexture(mTextureTarget, texId);
            GlUtil.checkGlError("glBindTexture " + texId);

            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER,
                    GLES20.GL_NEAREST);
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER,
                    GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S,
                    GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T,
                    GLES20.GL_CLAMP_TO_EDGE);
            GlUtil.checkGlError("glTexParameter");

            return texId;
        }

        private void prepareWindowSurface(Surface surface)
        {
            // Create a window surface, and attach it to the Surface we received.
            int[] surfaceAttribs = {
                    EGL14.EGL_NONE
            };
            eglSurface = EGL14.eglCreateWindowSurface(mEGLDisplay, mEGLConfig,surface ,
                    surfaceAttribs, 0);
            checkEglError("eglCreateWindowSurface");
            if (eglSurface == null) {
                throw new RuntimeException("surface was null");
            }
        }

        void createSurfaceTexture()
        {
            prepareWindowSurface(surfaceHolder.getSurface());
            makeCurrent(eglSurface);

            mProgramHandle = GlUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER_EXT);

            maPositionLoc = GLES20.glGetAttribLocation(mProgramHandle, "aPosition");
            GlUtil.checkLocation(maPositionLoc, "aPosition");
            maTextureCoordLoc = GLES20.glGetAttribLocation(mProgramHandle, "aTextureCoord");
            GlUtil.checkLocation(maTextureCoordLoc, "aTextureCoord");
            muMVPMatrixLoc = GLES20.glGetUniformLocation(mProgramHandle, "uMVPMatrix");
            GlUtil.checkLocation(muMVPMatrixLoc, "uMVPMatrix");
            muTexMatrixLoc = GLES20.glGetUniformLocation(mProgramHandle, "uTexMatrix");
            GlUtil.checkLocation(muTexMatrixLoc, "uTexMatrix");
            mTextureTarget = GLES11Ext.GL_TEXTURE_EXTERNAL_OES;
            mTextureId = createGLTextureObject();
            surfaceTexture = new SurfaceTexture(mTextureId);
        }
        /**
         * Issues the draw call.  Does the full setup on every call.
         *
         * @param mvpMatrix The 4x4 projection matrix.
         * @param vertexBuffer Buffer with vertex position data.
         * @param firstVertex Index of first vertex to use in vertexBuffer.
         * @param vertexCount Number of vertices in vertexBuffer.
         * @param coordsPerVertex The number of coordinates per vertex (e.g. x,y is 2).
         * @param vertexStride Width, in bytes, of the position data for each vertex (often
         *        vertexCount * sizeof(float)).
         * @param texMatrix A 4x4 transformation matrix for texture coords.  (Primarily intended
         *        for use with SurfaceTexture.)
         * @param texBuffer Buffer with vertex texture data.
         * @param texStride Width, in bytes, of the texture data for each vertex.
         */
        private void draw(float[] mvpMatrix, FloatBuffer vertexBuffer, int firstVertex,
                          int vertexCount, int coordsPerVertex, int vertexStride,
                          float[] texMatrix, FloatBuffer texBuffer, int textureId, int texStride) {
            GlUtil.checkGlError("draw start");

            // Select the program.
            GLES20.glUseProgram(mProgramHandle);
            GlUtil.checkGlError("glUseProgram");

            // Set the texture.
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(mTextureTarget, textureId);

            // Copy the model / view / projection matrix over.
            GLES20.glUniformMatrix4fv(muMVPMatrixLoc, 1, false, mvpMatrix, 0);
            GlUtil.checkGlError("glUniformMatrix4fv");

            // Copy the texture transformation matrix over.
            GLES20.glUniformMatrix4fv(muTexMatrixLoc, 1, false, texMatrix, 0);
            GlUtil.checkGlError("glUniformMatrix4fv");

            // Enable the "aPosition" vertex attribute.
            GLES20.glEnableVertexAttribArray(maPositionLoc);
            GlUtil.checkGlError("glEnableVertexAttribArray");

            // Connect vertexBuffer to "aPosition".
            GLES20.glVertexAttribPointer(maPositionLoc, coordsPerVertex,
                    GLES20.GL_FLOAT, false, vertexStride, vertexBuffer);
            GlUtil.checkGlError("glVertexAttribPointer");

            // Enable the "aTextureCoord" vertex attribute.
            GLES20.glEnableVertexAttribArray(maTextureCoordLoc);
            GlUtil.checkGlError("glEnableVertexAttribArray");

            // Connect texBuffer to "aTextureCoord".
            GLES20.glVertexAttribPointer(maTextureCoordLoc, 2,
                    GLES20.GL_FLOAT, false, texStride, texBuffer);
            GlUtil.checkGlError("glVertexAttribPointer");

            // Draw the rect.
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, firstVertex, vertexCount);
            GlUtil.checkGlError("glDrawArrays");

            // Done -- disable vertex array, texture, and program.
            GLES20.glDisableVertexAttribArray(maPositionLoc);
            GLES20.glDisableVertexAttribArray(maTextureCoordLoc);
            GLES20.glBindTexture(mTextureTarget, 0);
            GLES20.glUseProgram(0);
        }

        /**
         * Allocates a direct float buffer, and populates it with the float array data.
         */
        private FloatBuffer createFloatBuffer(float[] coords) {
            // Allocate a direct ByteBuffer, using 4 bytes per float, and copy coords into it.
            ByteBuffer bb = ByteBuffer.allocateDirect(coords.length * SIZEOF_FLOAT);
            bb.order(ByteOrder.nativeOrder());
            FloatBuffer fb = bb.asFloatBuffer();
            fb.put(coords);
            fb.position(0);
            return fb;
        }

        void drawFrame()
        {
            if(mEGLConfig!=null) {
                makeCurrent(eglSurface);
                if(VERBOSE) Log.d(TAG,"made current");
                //Get next frame from camera
                surfaceTexture.updateTexImage();
                surfaceTexture.getTransformMatrix(mTmpMatrix);

                //Fill the surfaceview with Camera frame
                int viewWidth = cameraView.getWidth();
                int viewHeight = cameraView.getHeight();
                if (frameCount == 0) {
                    if(VERBOSE)Log.d(TAG, "FRAME Count = "+frameCount);
                    Log.d(TAG,"SV Width == "+viewWidth+", SV Height == "+viewHeight);
                }
                GLES20.glViewport(0, 0, viewWidth, viewHeight);
                draw(IDENTITY_MATRIX, createFloatBuffer(FULL_RECTANGLE_COORDS), 0, (FULL_RECTANGLE_COORDS.length / 2), 2, 2 * SIZEOF_FLOAT, mTmpMatrix,
                        createFloatBuffer(FULL_RECTANGLE_TEX_COORDS), mTextureId, 2 * SIZEOF_FLOAT);

                if(VERBOSE)Log.d(TAG, "Draw on screen...."+isRecording);
                //Calls eglSwapBuffers.  Use this to "publish" the current frame.
                EGL14.eglSwapBuffers(mEGLDisplay, eglSurface);

                if(isRecording){
                    if(videoEncoderHandler!=null) {
                        makeCurrent(encoderSurface);
                        if(VERBOSE)Log.d(TAG,"Made encoder surface current");
                        GLES20.glViewport(0, 0, VIDEO_WIDTH, VIDEO_HEIGHT);
                        draw(IDENTITY_MATRIX, createFloatBuffer(FULL_RECTANGLE_COORDS), 0, (FULL_RECTANGLE_COORDS.length / 2), 2, 2 * SIZEOF_FLOAT, mTmpMatrix,
                                createFloatBuffer(FULL_RECTANGLE_TEX_COORDS), mTextureId, 2 * SIZEOF_FLOAT);
                        if(VERBOSE)Log.d(TAG,"Populated to encoder");
                        videoEncoderHandler.sendEmptyMessage(SAVE_VIDEO);
                        EGLExt.eglPresentationTimeANDROID(mEGLDisplay, encoderSurface, surfaceTexture.getTimestamp());
                        EGL14.eglSwapBuffers(mEGLDisplay, encoderSurface);
                    }
                    else{
                        Log.d(TAG,"Encoder not Ready yet...... Something wrong!!");
                    }
                }
                frameCount++;
            }
        }

        void shutdown()
        {
            Looper.myLooper().quit();
        }

        class CameraHandler extends Handler
        {
            WeakReference<CameraRenderer> cameraRender;

            public CameraHandler(CameraRenderer cameraRenderer){
                cameraRender = new WeakReference<>(cameraRenderer);
            }

            @Override
            public void handleMessage(Message msg) {
                CameraRenderer cameraRenderer = cameraRender.get();
                switch(msg.what)
                {
                    case SHUTDOWN:
                        Log.d(TAG,"Shutdown msg received");
                        cameraRenderer.shutdown();
                        break;
                    case FRAME_AVAILABLE:
                        if(VERBOSE)Log.d(TAG,"send to FRAME_AVAILABLE");
                        cameraRenderer.drawFrame();
                        break;
                }
            }
        }
    }

    private void prepareMuxer()
    {
        File file = new File(getExternalFilesDir(null),"myaudio.mp4");
        Log.d(TAG,"Saving MEDIA at == "+file.getPath());
        try {
            mediaMuxer = new MediaMuxer(file.getPath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        } catch (IOException e) {
            e.printStackTrace();
        }
        mediaMuxerHelper = new MediaMuxerHelper(mediaMuxer);
    }

    class VideoEncoder extends Thread
    {
        int count=0;
        ArrayList<FrameData> frames = new ArrayList<>();
        int trackIndex=0;

        @Override
        public void run() {
            Looper.prepare();
            videoEncoderHandler = new VideoEncoderHandler(this);
            setupVideoRecorder();
            prepareMuxer();
            Log.d(TAG,"Video encoder ready to accept frames");
            synchronized (renderObj){
                isReady=true;
                renderObj.notify();
            }
            Looper.loop();
            Log.d(TAG,"Video encoder STOPPED");
        }

        private void prepareWindowSurface(Surface surface)
        {
            // Create a window surface, and attach it to the Surface we received.
            int[] surfaceAttribs = {
                    EGL14.EGL_NONE
            };
            encoderSurface = EGL14.eglCreateWindowSurface(mEGLDisplay, mEGLConfig,surface ,
                    surfaceAttribs, 0);
            if(VERBOSE)Log.d(TAG,"Created Window Surface");
            checkEglError("eglCreateWindowSurface");
            if (eglSurface == null) {
                throw new RuntimeException("surface was null");
            }
        }

        private void checkEglError(String msg) {
            int error;
            if ((error = EGL14.eglGetError()) != EGL14.EGL_SUCCESS) {
                throw new RuntimeException(msg + ": EGL error: 0x" + Integer.toHexString(error));
            }
        }

        void closeEncoder()
        {
            Log.d(TAG,"STOP and RELEASE");
            videoCodec.signalEndOfInputStream();
            videoCodec.stop();
            videoSurface.release();
            videoCodec=null;
            mediaMuxerHelper.stopMuxer();
            mediaMuxerHelper.releaseMuxer();
            Log.d(TAG,"count the bytes");
            countBytes();
        }

        private void countBytes()
        {
            String bytes = "";
            if (count > 1000000){
                bytes = count/1000000+" MB";
            }
            else if(count > 1000){
                bytes = count/1000+" KB";
            }
            else{
                bytes = count+" Bytes";
            }
            Log.d(TAG,"Written "+bytes+" of data");
        }

        void shutdown()
        {
            Looper.myLooper().quit();
        }

        private void setupVideoRecorder()
        {
            camcorderProfile = CamcorderProfile.get(cameraId,CamcorderProfile.QUALITY_HIGH);
            if(VERBOSE) {
                Log.d(TAG, "VID Bit rate = " + camcorderProfile.videoBitRate);
                Log.d(TAG, "VID videoFrameRate = " + camcorderProfile.videoFrameRate);
                Log.d(TAG, "AUD audioBitRate = " + camcorderProfile.audioBitRate);
                Log.d(TAG, "AUD audioChannels = " + camcorderProfile.audioChannels);
                Log.d(TAG, "AUD audioSampleRate = " + camcorderProfile.audioSampleRate);
                Log.d(TAG, "AUD audioCodec = " + camcorderProfile.audioCodec);
            }
            videoFormat = MediaFormat.createVideoFormat(VIDEO_MIME_TYPE,VIDEO_WIDTH,VIDEO_HEIGHT);
            videoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            videoFormat.setInteger(MediaFormat.KEY_BIT_RATE, camcorderProfile.videoBitRate);
            videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, camcorderProfile.videoFrameRate);
            videoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
            try {
                videoCodec = MediaCodec.createEncoderByType(VIDEO_MIME_TYPE);
                videoCodec.configure(videoFormat,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);
                videoSurface = videoCodec.createInputSurface();
                videoCodec.start();
                prepareWindowSurface(videoSurface);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        private void drain()
        {
            if(videoCodec != null) {
                int videoBufferInd;
                MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
                //This loop will process one frame of data from Camera.
                ByteBuffer[] outputBuffers = videoCodec.getOutputBuffers();
                while (true) {
                    //Extract encoded data
                    //Log.d(TAG, "Retrieve Encoded Data....");
                    videoBufferInd = videoCodec.dequeueOutputBuffer(bufferInfo, TIMEOUT);
                /*if((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0){
                    Log.d(TAG,"EOS Reached...");
                    break;
                }*/
                    //Log.d(TAG, "OUTPUT buffer index = " + videoBufferInd);
                    if (videoBufferInd >= 0) {
                        if (bufferInfo.size != 0) {
                            outputBuffers[videoBufferInd].position(bufferInfo.offset);
                            bufferInfo.presentationTimeUs = System.nanoTime() / 1000;
                            outputBuffers[videoBufferInd].limit(bufferInfo.offset + bufferInfo.size);
                            //Log.d(TAG, "Writing data size == " + bufferInfo.size);
                            count += bufferInfo.size;
                            //frames.add(new FrameData(bufferInfo,outputBuffers[videoBufferInd]));
                            mediaMuxerHelper.recordMedia(videoCodec, bufferInfo, false, trackIndex, outputBuffers[videoBufferInd]);
                            videoCodec.releaseOutputBuffer(videoBufferInd, false);
                                /*if (isEOS || !isRecording) {
                                    break MAIN_LOOP;
                                }*/
                        }
                    } else if (videoBufferInd == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                        outputBuffers = videoCodec.getOutputBuffers();
                    } else if (videoBufferInd == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        // Subsequent data will conform to new format.
                        videoFormat = videoCodec.getOutputFormat();
                        trackIndex = mediaMuxerHelper.addTrack(videoFormat, false);
                        //isVideoAdded = true;
                        mediaMuxerHelper.startMuxer();
                    } else if (videoBufferInd == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        //Log.d(TAG, "Coming out since no more frame data to read");
                        break;
                    }
                }
            }
        }

        public VideoEncoderHandler getHandler()
        {
            return videoEncoderHandler;
        }

        class VideoEncoderHandler extends Handler
        {
            WeakReference<VideoEncoder> encoder;
            public VideoEncoderHandler(VideoEncoder videoEncoder)
            {
                encoder = new WeakReference<>(videoEncoder);
            }

            @Override
            public void handleMessage(Message msg) {
                VideoEncoder enc = encoder.get();
                switch(msg.what)
                {
                    case RECORD_STOP:
                        enc.drain();
                        enc.closeEncoder();
                        break;
                    case SHUTDOWN:
                        enc.shutdown();
                        break;
                    case SAVE_VIDEO:
                        enc.drain();
                        break;
                }
            }
        }
    }

    class MediaMuxerHelper
    {
        MediaMuxer mediaMuxer;
        boolean muxerStarted=false;

        public MediaMuxerHelper(MediaMuxer mMuxer)
        {
            mediaMuxer = mMuxer;
        }

        public void recordMedia(MediaCodec mediaCodec, MediaCodec.BufferInfo bufferInfo, boolean audioTrack,int trackIndex,ByteBuffer mediaData)
        {
            //Extract encoded data
            //Log.d(TAG,"Recording for "+(audioTrack ? "AUDIO" : "VIDEO"));
            mediaMuxer.writeSampleData(trackIndex, mediaData, bufferInfo);
        }

        public int addTrack(MediaFormat mediaFormat,boolean audioTrack)
        {
            Log.d(TAG,"adding track for "+(audioTrack ? "AUDIO" : "VIDEO"));
            return mediaMuxer.addTrack(mediaFormat);
        }

        private void releaseMuxer()
        {
            if(mediaMuxer!=null) {
                mediaMuxer.release();
                mediaMuxer=null;
            }
        }

        public void startMuxer()
        {
            if(mediaMuxer!=null) {
                if (!muxerStarted) {
                    mediaMuxer.start();
                    muxerStarted=true;
                }
            }
        }

        private void stopMuxer()
        {
            if(mediaMuxer!=null){
                if(muxerStarted){
                    mediaMuxer.stop();
                    muxerStarted=false;
                }
            }
        }
    }
}