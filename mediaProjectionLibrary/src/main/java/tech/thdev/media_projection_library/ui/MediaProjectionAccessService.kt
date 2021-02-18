package tech.thdev.media_projection_library.ui

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.Log
import android.view.Surface
import tech.thdev.media_projection_library.MediaProjectionStatus
import tech.thdev.media_projection_library.MediaProjectionStatusData
import tech.thdev.media_projection_library.R
import tech.thdev.media_projection_library.constant.ACTION_INIT
import tech.thdev.media_projection_library.constant.ACTION_PERMISSION_INIT
import tech.thdev.media_projection_library.constant.ACTION_REJECT
import tech.thdev.media_projection_library.constant.ACTION_SELF_STOP
import tech.thdev.media_projection_library.constant.ACTION_START
import tech.thdev.media_projection_library.constant.ACTION_STOP
import tech.thdev.media_projection_library.constant.DEFAULT_VALUE_PROJECTION_NAME
import tech.thdev.media_projection_library.constant.DEFAULT_VALUE_SIZE_HEIGHT
import tech.thdev.media_projection_library.constant.DEFAULT_VALUE_SIZE_WIDTH
import tech.thdev.media_projection_library.constant.EXTRA_PROJECTION_NAME
import tech.thdev.media_projection_library.constant.EXTRA_REQUEST_DATA
import tech.thdev.media_projection_library.constant.EXTRA_RESULT_CODE
import tech.thdev.media_projection_library.constant.EXTRA_SIZE_HEIGHT
import tech.thdev.media_projection_library.constant.EXTRA_SIZE_WIDTH
import tech.thdev.media_projection_library.constant.EXTRA_SURFACE
import java.io.FileOutputStream


open class MediaProjectionAccessService : Service() {

    private lateinit var mediaProjection: MediaProjection
    private lateinit var virtualDisplay: VirtualDisplay

    companion object {
        private const val FOREGROUND_SERVICE_ID = 1000

        private const val CHANNEL_ID = "MediaProjectionService"

        fun newService(
                context: Context
        ): Intent =
                Intent(context, MediaProjectionAccessService::class.java).apply {
                    action = ACTION_INIT
                }

        fun newStartMediaProjection(
            context: Context,
            surface: Surface,
            projectionName: String = DEFAULT_VALUE_PROJECTION_NAME,
            width: Int = DEFAULT_VALUE_SIZE_WIDTH,
            height: Int = DEFAULT_VALUE_SIZE_HEIGHT
        ): Intent =
                newService(context).apply {
                    putExtra(EXTRA_SURFACE, surface)
                    putExtra(EXTRA_PROJECTION_NAME, projectionName)
                    putExtra(EXTRA_SIZE_WIDTH, width)
                    putExtra(EXTRA_SIZE_HEIGHT, height)
                    action = ACTION_START
                }

        fun newStopMediaProjection(
                context: Context
        ): Intent =
                newService(context).apply {
                    action = ACTION_STOP
                }

        fun newStopService(
                context: Context
        ): Intent =
                newService(context).apply {
                    action = ACTION_SELF_STOP
                }
    }

    private val mediaProjectionManager: MediaProjectionManager by lazy {
        getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundService()
    }

    open fun startForegroundService() {
        createNotificationChannel()
        val notificationIntent = Intent(this, MediaProjectionAccessActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
                this,
                0, notificationIntent, 0
        )

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
                .setContentTitle("Foreground Service")
                .setSmallIcon(R.drawable.ic_baseline_fiber_manual_record_24)
                .setContentIntent(pendingIntent)
                .build()
        startForeground(FOREGROUND_SERVICE_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        action(intent)
        return START_REDELIVER_INTENT
    }

    private fun action(intent: Intent?) {
        when (intent?.action) {
            ACTION_INIT -> getPermission(intent)
            ACTION_PERMISSION_INIT -> permissionInitMediaProjection(intent)
            ACTION_REJECT -> rejectMediaProjection()
            ACTION_START -> startMediaProjection(intent)
            ACTION_STOP -> stopMediaProjection()
            ACTION_SELF_STOP -> stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun createMediaProjection() {
        getPermission()
    }

    private fun getPermission(intent: Intent? = null) {
        initRegisterReceiver()
        startActivity(MediaProjectionAccessActivity.newInstance(this))
    }

    private fun initRegisterReceiver() {
        MediaProjectionAccessBroadcastReceiver.register(this, ::action)
    }

    private fun unregisterReceiver() {
        MediaProjectionAccessBroadcastReceiver.unregister(this)
    }

    private fun permissionInitMediaProjection(intent: Intent) {
        unregisterReceiver()
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data = intent.getParcelableExtra<Intent>(EXTRA_REQUEST_DATA)
        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data!!)
        mediaProjection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                sendEvent(MediaProjectionStatus.OnStop)
            }
        }, null)
        sendEvent(MediaProjectionStatus.OnInitialized)
    }

    private fun rejectMediaProjection() {
        unregisterReceiver()
        sendEvent(MediaProjectionStatus.OnReject)
    }

    private fun startMediaProjection(intent: Intent) {
        startMediaProjection(
            surface = intent.getParcelableExtra(EXTRA_SURFACE) as Surface,
            projectionName = intent.getStringExtra(EXTRA_PROJECTION_NAME) ?: DEFAULT_VALUE_PROJECTION_NAME,
            width = intent.getIntExtra(EXTRA_SIZE_WIDTH, DEFAULT_VALUE_SIZE_WIDTH),
            height = intent.getIntExtra(EXTRA_SIZE_HEIGHT, DEFAULT_VALUE_SIZE_HEIGHT)
        )
    }

    fun startMediaProjection(
        surface: Surface,
        projectionName: String = DEFAULT_VALUE_PROJECTION_NAME,
        width: Int = DEFAULT_VALUE_SIZE_WIDTH,
        height: Int = DEFAULT_VALUE_SIZE_HEIGHT
    ) {
        if (::mediaProjection.isInitialized) {
            virtualDisplay = mediaProjection.createVirtualDisplay(
                    projectionName,
                    width,
                    height,
                    application.resources.displayMetrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
//                surface,
                    createImageReader().surface,
                    null,
                    null
            )
            sendEvent(MediaProjectionStatus.OnStarted)
        } else {
            sendEvent(MediaProjectionStatus.OnFail)
        }
    }

    private fun createImageReader(): ImageReader {
        var num = 0

        val size = DeviceSize(this).size
        val imageReader = ImageReader.newInstance(size.width, size.height, PixelFormat.RGBA_8888, 2);


        imageReader.setOnImageAvailableListener(
                { reader ->

                    try {
                        check(reader != null)

                        reader.acquireLatestImage().use { image ->
                            val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                                    .resolve("가상디스플레이사이즈-통일")

                            if (!root.exists()) root.mkdirs()

                            val path = root.resolve("${"%04d".format(num)}.jpg")


                            num++
                            FileOutputStream(path).use { fos ->
                                val planes = image.planes;
                                val pixelStride = planes.first().pixelStride
                                val rowStride = planes.first().rowStride
                                val rowPadding = rowStride - pixelStride * imageReader.width;
                                val buffer = planes[0].buffer.rewind();
                                val extra = rowPadding / pixelStride
                                val width = imageReader.width + extra
                                val height = imageReader.height
                                val bitmap = Bitmap.createBitmap(
                                        width,
                                        height,
                                        Bitmap.Config.ARGB_8888
                                )

                                Log.d("IMAGE_SIZE", "($width, $height) pixelStride: $pixelStride rowStride: $rowStride rowPadding: $rowPadding, extra: $extra")

                                bitmap.copyPixelsFromBuffer(buffer);
                                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, fos)
                            }
                            Log.d("IMAGE_OK", "IMAGE OK")
                        }
                    } catch (t: Throwable) {
                        Log.e("IMAGE_SAVE", "${t.message}")
                        t.printStackTrace()
                    }

                },
                null
        )
        return imageReader
    }

    fun stopMediaProjection() {
        try {
            if (::mediaProjection.isInitialized) {
                mediaProjection.stop()
            }
            if (::virtualDisplay.isInitialized) {
                virtualDisplay.release()
            }
        } catch (e: Exception) {
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                    CHANNEL_ID,
                    "Foreground Service Channel",
                    NotificationManager.IMPORTANCE_DEFAULT
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).apply {
                createNotificationChannel(serviceChannel)
            }
        }
    }

    private fun sendEvent(status: MediaProjectionStatus) {
        val data = MediaProjectionStatusData(status)
        sendBroadcast(MediaProjectionAccessServiceBroadcastReceiver.newInstance(data))
        onChangeStatus(data)
    }

    open fun onChangeStatus(statusData: MediaProjectionStatusData) {
        // Do nothing
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMediaProjection()
    }
}