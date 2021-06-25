package com.newland.camera

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.util.SparseIntArray
import android.view.*
import android.view.ViewTreeObserver.OnGlobalLayoutListener
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.app.ActivityCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.*
import com.chad.library.adapter.base.BaseQuickAdapter
import com.chad.library.adapter.base.listener.OnItemClickListener
import com.newland.camera.beans.TakeOperation
import com.newland.camera.common.TakeOptionConstant
import com.newland.camera.manager.FileManager
import com.newland.camera.utils.Camera2Utils
import com.newland.camera.utils.CameraUtils
import com.newland.camera.utils.GlideUtils
import com.newland.camera.widget.CameraConstraintLayout
import com.newland.camera.widget.CameraConstraintLayout.OnSwitchListener
import com.newland.camera.widget.TakePhotoButton
import com.newland.camera.widget.center.CenterItemDecoration
import com.newland.camera.widget.center.CenterLayoutManager
import com.newland.camera.widget.center.CenterRecyclerView
import com.newland.ui.adapter.MenuAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {
    companion object {
        val ORIENTATIONS: SparseIntArray = SparseIntArray()

        init {
            ORIENTATIONS.append(Surface.ROTATION_0, 90)
            ORIENTATIONS.append(Surface.ROTATION_90, 0)
            ORIENTATIONS.append(Surface.ROTATION_180, 270)
            ORIENTATIONS.append(Surface.ROTATION_270, 180)
        }
    }

    private val indicatorTake: CenterRecyclerView by lazy { findViewById(R.id.indicator_take) }
    private val takePhotoBtn: TakePhotoButton by lazy { findViewById(R.id.btn_takephoto) }
    private val bottomLayout: View by lazy { findViewById(R.id.layout_bottom) }
    private val topLayout: View by lazy { findViewById(R.id.layout_top) }
    private val surfaceView: SurfaceView by lazy { findViewById(R.id.surfaceView) }
    private val cameraConstraintLayout: CameraConstraintLayout by lazy { findViewById(R.id.camera_constraintlayout) }
    private val flashIb: ImageButton by lazy { findViewById(R.id.ib_flash) }
    private val timerIb: ImageButton by lazy { findViewById(R.id.ib_timer) }
    private val filterIb: ImageButton by lazy { findViewById(R.id.ib_filter) }
    private val flashAutoTv: AppCompatTextView by lazy { findViewById(R.id.tv_flash_auto) }
    private val flashOpenTv: AppCompatTextView by lazy { findViewById(R.id.tv_flash_open) }
    private val flashCloseIb: AppCompatTextView by lazy { findViewById(R.id.tv_flash_close) }
    private val closeTimerTv: AppCompatTextView by lazy { findViewById(R.id.tv_timer_close) }
    private val timer3sTv: AppCompatTextView by lazy { findViewById(R.id.tv_timer_3s) }
    private val timer10sTv: AppCompatTextView by lazy { findViewById(R.id.tv_timer_10s) }
    private val adjustIv: AppCompatImageView by lazy { findViewById(R.id.icon_adjust) }
    private val overlay: View by lazy { findViewById(R.id.overlay) }

    private val animationTask: Runnable by lazy {
        Runnable {
            overlay.visibility = View.VISIBLE
            overlay.background = Color.argb(150, 255, 255, 255).toDrawable()
            overlay.postDelayed({
                overlay.background = null
                overlay.visibility = View.GONE
            }, 50)
        }
    }

    private val mCameraManager: CameraManager by lazy { getSystemService(Context.CAMERA_SERVICE) as CameraManager }
    var mCameraDevice: CameraDevice? = null
    lateinit var mCameraId: String
    var mCameraCaptureSession: CameraCaptureSession? = null
    lateinit var mImageReader: ImageReader

    private val mMainHandler by lazy { Handler(getMainLooper()) }
    private val childHandlerThread = HandlerThread("Cameraphone").apply {
        start()
    }
    private val childHandler: Handler = Handler(childHandlerThread.looper)
    private var isDisconnectedCamera = false
    private var isResume = false
    private var takeOperation: TakeOperation? = null
    var mStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(cameraDevice: CameraDevice) {
            mCameraDevice = cameraDevice
            takePreview()
        }

        override fun onDisconnected(cameraDevice: CameraDevice) {
            if (isResume) {
                resetCamera()
            }
        }

        override fun onError(cameraDevice: CameraDevice, p1: Int) {
        }

    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)
        initTakeOperationView()
        initSurfaceView()
        flashIb.setOnClickListener {
            if (flashAutoTv.visibility == View.VISIBLE) {
                timerIb.visibility = View.VISIBLE
                filterIb.visibility = View.VISIBLE
                flashAutoTv.visibility = View.GONE
                flashOpenTv.visibility = View.GONE
                flashCloseIb.visibility = View.GONE
            } else {
                timerIb.visibility = View.GONE
                filterIb.visibility = View.GONE
                flashAutoTv.visibility = View.VISIBLE
                flashOpenTv.visibility = View.VISIBLE
                flashCloseIb.visibility = View.VISIBLE
            }
        }
        timerIb.setOnClickListener {
            if (closeTimerTv.visibility == View.VISIBLE) {
                closeTimerTv.visibility = View.GONE
                timer3sTv.visibility = View.GONE
                timer10sTv.visibility = View.GONE
                var targetX: Float =
                    (((timerIb.parent as ViewGroup).width - timerIb.width) / 2).toFloat()
                var animator = ObjectAnimator.ofFloat(timerIb, "x", timerIb.x, targetX)
                animator.duration = 500
                animator.addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator?) {
                        flashIb.visibility = View.VISIBLE
                        filterIb.visibility = View.VISIBLE
                    }
                })
                animator.start()
            } else {
                flashIb.visibility = View.GONE
                filterIb.visibility = View.GONE
                var animator = ObjectAnimator.ofFloat(timerIb, "x", timerIb.x, flashIb.x)
                animator.duration = 800
                animator.addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator?) {
                        closeTimerTv.visibility = View.VISIBLE
                        timer3sTv.visibility = View.VISIBLE
                        timer10sTv.visibility = View.VISIBLE
                    }
                })
                animator.start()
            }
        }

    }

    override fun onResume() {
        super.onResume()
        isResume = true
        if (isDisconnectedCamera) {
            resetCamera()
        }
    }

    override fun onPause() {
        super.onPause()
        isResume = false
    }

    override fun onDestroy() {
        super.onDestroy()
        childHandlerThread?.apply {
            quitSafely()
            join()
        }
    }

    private fun resetCamera() {
        stopCamera()
        initCamera()
    }

    private fun initCamera() {
        mCameraId = CameraUtils.getFirstCameraIdFacing(mCameraManager)
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        mCameraManager.openCamera(mCameraId, mStateCallback, mMainHandler)
    }

    private fun stopCamera() {
        mCameraDevice?.apply {
            close()
            mCameraDevice = null
        }
    }

    private fun initSurfaceView() {
        surfaceView.viewTreeObserver.addOnGlobalLayoutListener(object : OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                surfaceView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                adjustSurfaceView()
                initCamera()
            }
        })
    }

    private fun adjustSurfaceView() {
        takeOperation?.also {
            topLayout.visibility = VISIBLE
            var layoutParams = surfaceView.layoutParams
            when (it.flag) {
                TakeOptionConstant.TAKE_PHOTO -> {
                    layoutParams.height =
                        (bottomLayout.y - topLayout.y - topLayout.height).toInt()
                    bottomLayout.setBackgroundResource(R.color.color_theme_color)
                }
                TakeOptionConstant.SEQUARE -> {
                    layoutParams.height = surfaceView.width
                    bottomLayout.setBackgroundResource(R.color.color_theme_color)
                }
                TakeOptionConstant.FULL -> {
                    layoutParams.height = LayoutParams.MATCH_PARENT
                    topLayout.visibility = GONE
                    bottomLayout.setBackgroundResource(R.color.color_take_half_transpant)
                }
            }
            surfaceView.layoutParams = layoutParams
            overlay.layoutParams.also { itt ->
                itt.height = layoutParams.height
                overlay.layoutParams = itt
            }
        }
    }

    private fun initTakeOperationView() {
        val datas = TakeOptionConstant.getTakeOperations()
        var adapter = MenuAdapter(datas)
        adapter.setOnItemClickListener(object : OnItemClickListener {
            override fun onItemClick(adapter: BaseQuickAdapter<*, *>, view: View, position: Int) {
                indicatorTake.smoothScrollToPosition(position)
            }
        })
        indicatorTake.mOnTargetItemListener = object : CenterRecyclerView.OnTargetItemListener {
            override fun onTargetItem(position: Int, prePosition: Int) {
                adapter.refreshTakeOperation(position, prePosition)
                var option = adapter.data[position]
                if (option.flag != takeOperation?.flag) {
                    takeOperation = option
                    adjustSurfaceView()
                    resetCamera()
                }
            }
        }
        indicatorTake.layoutManager =
            CenterLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        indicatorTake.addItemDecoration(CenterItemDecoration())
        indicatorTake.adapter = adapter
        for (i in datas.indices) {
            if (datas[i].flag == TakeOptionConstant.TAKE_PHOTO) {
                takeOperation = datas[i]
                indicatorTake.setInitPosition(i)
                takePhotoBtn.type = takeOperation!!.flag
                break
            }
        }
        cameraConstraintLayout.mOnSwitchListener = object : OnSwitchListener {
            override fun onPrev() {
                indicatorTake.smoothScrollToPosition(indicatorTake.mPosition - 1)
            }

            override fun onNext() {
                indicatorTake.smoothScrollToPosition(indicatorTake.mPosition + 1)
            }
        }
        takePhotoBtn.setOnClickListener {
            takePhoto()
        }
    }

    fun takePhoto() {
        mCameraDevice?.apply {
            val captureRequestBuild = createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureRequestBuild.addTarget(mImageReader.surface)
            captureRequestBuild.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
            captureRequestBuild.set(
                CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
            )
            var rotation: Int = this@MainActivity.windowManager.defaultDisplay.rotation
            captureRequestBuild.set(CaptureRequest.JPEG_ORIENTATION, ORIENTATIONS[rotation])
            mCameraCaptureSession?.capture(
                captureRequestBuild.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureStarted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        timestamp: Long,
                        frameNumber: Long
                    ) {
                        surfaceView.post(animationTask)
                    }
                },
                childHandler
            )
        }
    }

    fun takePreview() {
        val size = CameraUtils.getOutputSize(
            surfaceView.display,
            mCameraManager,
            mCameraId,
            surfaceView.measuredWidth,
            surfaceView.measuredHeight,
            SurfaceHolder::class.java
        )
        surfaceView.holder.setFixedSize(size.width, size.height)
        val irSize = CameraUtils.getOutputSize(
            surfaceView.display,
            mCameraManager,
            mCameraId,
            surfaceView.measuredWidth,
            surfaceView.measuredHeight,
            ImageReader::class.java,
            ImageFormat.JPEG
        )
        mImageReader =
            ImageReader.newInstance(irSize.width, irSize.height, ImageFormat.JPEG, 10)
        mImageReader.setOnImageAvailableListener({ reader ->
            reader?.also { reader ->
                var image = reader.acquireNextImage()
                var buffer = image.planes[0].buffer
                var bytes = buffer.remaining().let { ByteArray(it) }
                buffer.get(bytes)
                var file = FileManager.instance.getPicture("${System.currentTimeMillis()}.jpg")
                var fos = FileOutputStream(file)
                fos.write(bytes)
                fos.flush()
                fos.close()
                var intent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
                var uri = Uri.fromFile(File(file))
                intent.data = uri
                sendBroadcast(intent)
                lifecycleScope.launch(Dispatchers.Main) {
                    GlideUtils.loadImage(this@MainActivity, file, adjustIv)
                }
                if (mImageReader.maxImages >= 10) {
                    lifecycleScope.launch(Dispatchers.Main) {
                        resetCamera()
                    }
                }
            }
        }, childHandler)
        var previewSurface = surfaceView.holder.surface
        var targets = listOf(previewSurface, mImageReader.surface)
        mCameraDevice?.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                mCameraDevice?.apply {
                    mCameraCaptureSession = session
                    val captureRequest = createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                    captureRequest.addTarget(previewSurface)
                    captureRequest.set(
                        CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                    )
                    captureRequest.set(
                        CaptureRequest.CONTROL_AE_MODE,
                        CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
                    )
                    session.setRepeatingRequest(captureRequest.build(), null, childHandler)
                }
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
            }

        }, childHandler)

    }
}