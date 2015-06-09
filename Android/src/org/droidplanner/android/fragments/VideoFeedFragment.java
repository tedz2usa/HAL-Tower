package org.droidplanner.android.fragments;

import android.app.Fragment;
import android.graphics.SurfaceTexture;
import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.os.Environment;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.ImageButton;
import android.widget.Toast;
import android.widget.ToggleButton;

import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.USBMonitor.OnDeviceConnectListener;
import com.serenegiant.usb.UVCCamera;

import org.droidplanner.android.R;
import org.droidplanner.android.usb.CameraDialog;
import org.droidplanner.android.video.Encoder;
import org.droidplanner.android.video.SurfaceEncoder;
import org.droidplanner.android.widget.UVCCameraTextureView;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;



/**
 * Created by converge-devan on 5/15/15.
 */
public class VideoFeedFragment extends Fragment {

    private static final boolean DEBUG = true;    // set false when releasing
    private static final String TAG = "VideoFeedFragment";

    // for thread pool
    private static final int CORE_POOL_SIZE = 1;        // initial/minimum threads
    private static final int MAX_POOL_SIZE = 4;            // maximum threads
    private static final int KEEP_ALIVE_TIME = 10;        // time periods while keep the idle thread
    protected static final ThreadPoolExecutor EXECUTER
            = new ThreadPoolExecutor(CORE_POOL_SIZE, MAX_POOL_SIZE, KEEP_ALIVE_TIME,
            TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>());

    private static final int CAPTURE_STOP = 0;
    private static final int CAPTURE_PREPARE = 1;
    private static final int CAPTURE_RUNNING = 2;

    // for accessing USB and USB camera
    private static USBMonitor mUSBMonitor;
    private UVCCamera mUVCCamera;
    private UVCCameraTextureView mUVCCameraView;
    // for open&start / stop&close camera preview
    private ToggleButton mCameraButton;
    // for start & stop movie capture
    private ImageButton mCaptureButton;

    private int mCaptureState = 0;
    private Surface mPreviewSurface;


    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View view = inflater.inflate(R.layout.fragment_video_feed, container, false);

        mCameraButton = (ToggleButton) view.findViewById(R.id.camera_button);
        mCameraButton.setOnCheckedChangeListener(mOnCheckedChangeListener);

        mCaptureButton = (ImageButton) view.findViewById(R.id.capture_button);
        mCaptureButton.setOnClickListener(mOnClickListener);

        mUVCCameraView = (UVCCameraTextureView) view.findViewById(R.id.UVCCameraTextureView1);
        mUVCCameraView.setAspectRatio(UVCCamera.DEFAULT_PREVIEW_WIDTH / (float) UVCCamera.DEFAULT_PREVIEW_HEIGHT);
        mUVCCameraView.setSurfaceTextureListener(mSurfaceTextureListener);

        mUSBMonitor = new USBMonitor(getActivity(), mOnDeviceConnectListener);
        return view;
    }


    @Override
    public void onResume() {
        super.onResume();
        mUSBMonitor.register();
        if (mUVCCamera != null)
            mUVCCamera.startPreview();
        updateItems();
    }

    @Override
    public void onPause() {
        if (mUVCCamera != null) {
            stopCapture();
            mUVCCamera.stopPreview();
        }
        mUSBMonitor.unregister();
        super.onPause();
    }

    @Override
    public void onDestroy() {
        if (mUVCCamera != null) {
            mUVCCamera.destroy();
            mUVCCamera = null;
        }
        if (mUSBMonitor != null) {
            mUSBMonitor.destroy();
            mUSBMonitor = null;
        }
        mCameraButton = null;
        mCaptureButton = null;
        mUVCCameraView = null;
        super.onDestroy();
    }

    private final CompoundButton.OnCheckedChangeListener mOnCheckedChangeListener = new CompoundButton.OnCheckedChangeListener() {
        @Override
        public void onCheckedChanged(final CompoundButton buttonView, final boolean isChecked) {
            if (isChecked && mUVCCamera == null) {
                CameraDialog.showDialog(getActivity());
            } else if (mUVCCamera != null) {
                mUVCCamera.destroy();
                mUVCCamera = null;
            }
            updateItems();
        }
    };

    private final View.OnClickListener mOnClickListener = new View.OnClickListener() {
        @Override
        public void onClick(final View v) {
            if (mCaptureState == CAPTURE_STOP) {
                startCapture();
            } else {
                stopCapture();
            }
        }
    };

    private final OnDeviceConnectListener mOnDeviceConnectListener = new OnDeviceConnectListener() {
        @Override
        public void onAttach(final UsbDevice device) {
            Toast.makeText(getActivity(), "USB_DEVICE_ATTACHED", Toast.LENGTH_SHORT).show();

            //autoAttach(device);

        }

        @Override
        public void onConnect(final UsbDevice device, final USBMonitor.UsbControlBlock ctrlBlock, final boolean createNew) {
            if (mUVCCamera != null)
                mUVCCamera.destroy();
            mUVCCamera = new UVCCamera();
            Log.d("Main - OnDevice - OnCon", "Here");
            EXECUTER.execute(new Runnable() {
                @Override
                public void run() {
                    mUVCCamera.open(ctrlBlock);
                    if (mPreviewSurface != null) {
                        mPreviewSurface.release();
                        mPreviewSurface = null;
                    }
                    try {
                        mUVCCamera.setPreviewSize(UVCCamera.DEFAULT_PREVIEW_WIDTH, UVCCamera.DEFAULT_PREVIEW_HEIGHT, UVCCamera.FRAME_FORMAT_MJPEG);
                    } catch (final IllegalArgumentException e) {
                        try {
                            // fallback to YUV mode
                            mUVCCamera.setPreviewSize(UVCCamera.DEFAULT_PREVIEW_WIDTH, UVCCamera.DEFAULT_PREVIEW_HEIGHT, UVCCamera.DEFAULT_PREVIEW_MODE);
                        } catch (final IllegalArgumentException e1) {
                            mUVCCamera.destroy();
                            mUVCCamera = null;
                        }
                    }
                    if (mUVCCamera != null) {
                        final SurfaceTexture st = mUVCCameraView.getSurfaceTexture();
                        if (st != null)
                            mPreviewSurface = new Surface(st);
                        mUVCCamera.setPreviewDisplay(mPreviewSurface);
                        mUVCCamera.startPreview();
                    }
                }
            });
        }

        @Override
        public void onDisconnect(final UsbDevice device, final USBMonitor.UsbControlBlock ctrlBlock) {
            // XXX you should check whether the comming device equal to camera device that currently using
            if (mUVCCamera != null) {
                mUVCCamera.close();
                if (mPreviewSurface != null) {
                    mPreviewSurface.release();
                    mPreviewSurface = null;
                }
            }
        }

        @Override
        public void onDettach(final UsbDevice device) {
            //Toast.makeText(MainActivity.this, "USB_DEVICE_DETACHED", Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onCancel() {
        }
    };

    /**
     * Function to automatically connect to a UVC camera when attached
     */
    public void autoAttach(final UsbDevice device) {

        //Code from mOnCheckedChangedListener in VideoFeedFragment.java
        if (mUVCCamera == null) {
            CameraDialog.showDialog(getActivity());
        } else if (mUVCCamera != null) {
            mUVCCamera.destroy();
            mUVCCamera = null;
        }
        Log.d("autoAttach","UpdateItems");
        updateItems();


//        if (mUSBMonitor == null)
//            try {
//                mUSBMonitor = getUSBMonitor();
//            } catch (ClassCastException e) {
//            } catch (NullPointerException e) {
//            }
//        if (mUSBMonitor == null) {
//            //throw new ClassCastException(activity.toString() + " must implement #getUSBController");
//            Log.d("OnDevCon OnAtt", "USB Monitor NUll");
//        }
//
//
//        mUSBMonitor.requestPermission(device);


    };


    /**
     * to access from CameraDialog
     * @return
     */
    public static USBMonitor getUSBMonitor() {
        return mUSBMonitor;
    }

    //**********************************************************************
    private final TextureView.SurfaceTextureListener mSurfaceTextureListener = new TextureView.SurfaceTextureListener() {

        @Override
        public void onSurfaceTextureAvailable(final SurfaceTexture surface, final int width, final int height) {
        }

        @Override
        public void onSurfaceTextureSizeChanged(final SurfaceTexture surface, final int width, final int height) {
        }

        @Override
        public boolean onSurfaceTextureDestroyed(final SurfaceTexture surface) {
            if (mPreviewSurface != null) {
                mPreviewSurface.release();
                mPreviewSurface = null;
            }
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(final SurfaceTexture surface) {
            if (mEncoder != null && mCaptureState == CAPTURE_RUNNING) {
                mEncoder.frameAvailable();
            }
        }
    };

    private Encoder mEncoder;
    /**
     * start capturing
     */
    private final void startCapture() {
        if (DEBUG) Log.v(TAG, "startCapture:");
        if (mEncoder == null && (mCaptureState == CAPTURE_STOP)) {
            mCaptureState = CAPTURE_PREPARE;
            EXECUTER.execute(new Runnable() {
                @Override
                public void run() {
                    final String path = getCaptureFile(Environment.DIRECTORY_MOVIES, ".mp4");
                    if (!TextUtils.isEmpty(path)) {
                        mEncoder = new SurfaceEncoder(path);
                        mEncoder.setEncodeListener(mEncodeListener);
                        try {
                            mEncoder.prepare();
                            mEncoder.startRecording();
                        } catch (final IOException e) {
                            mCaptureState = CAPTURE_STOP;
                        }
                    } else
                        throw new RuntimeException("Failed to start capture.");
                }
            });
            updateItems();
        }
    }

    /**
     * stop capture if capturing
     */
    private final void stopCapture() {
        if (DEBUG) Log.v(TAG, "stopCapture:");
        if (mEncoder != null) {
            mEncoder.stopRecording();
            mEncoder = null;
        }
    }

    /**
     * callbackds from Encoder
     */
    private final Encoder.EncodeListener mEncodeListener = new Encoder.EncodeListener() {
        @Override
        public void onPreapared(final Encoder encoder) {
            if (DEBUG) Log.v(TAG, "onPreapared:");
            mUVCCamera.startCapture(((SurfaceEncoder)encoder).getInputSurface());
            mCaptureState = CAPTURE_RUNNING;
        }
        @Override
        public void onRelease(final Encoder encoder) {
            if (DEBUG) Log.v(TAG, "onRelease:");
            mUVCCamera.stopCapture();
            mCaptureState = CAPTURE_STOP;
            updateItems();
        }
    };

    private void updateItems() {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mCaptureButton.setVisibility(mCameraButton.isChecked() ? View.VISIBLE : View.INVISIBLE);
                mCaptureButton.setColorFilter(mCaptureState == CAPTURE_STOP ? 0 : 0xffff0000);
            }
        });
    }

    /**
     * create file path for saving movie / still image file
     * @param type Environment.DIRECTORY_MOVIES / Environment.DIRECTORY_DCIM
     * @param ext .mp4 / .png
     * @return return null if can not write to storage
     */
    private static final String getCaptureFile(final String type, final String ext) {
        final File dir = new File(Environment.getExternalStoragePublicDirectory(type), "USBCameraTest");
        dir.mkdirs();	// create directories if they do not exist
        if (dir.canWrite()) {
            return (new File(dir, getDateTimeString() + ext)).toString();
        }
        return null;
    }

    private static final SimpleDateFormat sDateTimeFormat = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US);
    private static final String getDateTimeString() {
        final GregorianCalendar now = new GregorianCalendar();
        return sDateTimeFormat.format(now.getTime());
    }


}


