/*
 * Copyright (C) 2020 Orange
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.devicefarmer.minicap.provider

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.net.LocalSocket
import android.os.Build
import android.util.Size
import io.devicefarmer.minicap.output.DisplayOutput
import io.devicefarmer.minicap.output.MinicapClientOutput
import io.devicefarmer.minicap.SimpleServer
import io.devicefarmer.minicap.utils.LoggerFactory
import java.io.OutputStream
import java.io.PrintStream
import java.nio.ByteBuffer
import kotlin.jvm.java

/**
 * Base class to provide images of the screen. Those captures can be setup from SurfaceControl - as
 * it currently is - but could as well comes from MediaProjection API if useful in a future use case.
 * It basically receives screen images, do whatever processing needed (here, encodes in jpeg format)
 * and sends the results to an output (could be a file for screenshot, or a minicap client receiving the
 * jpeg stream)
 */
abstract class BaseProvider(protected val displayId: Int, private val targetSize: Size, val rotation: Int) : SimpleServer.Listener,
    ImageReader.OnImageAvailableListener {

    companion object {
        val log = LoggerFactory.getLogger()
    }

    private lateinit var clientOutput: DisplayOutput
    private lateinit var imageReader: ImageReader
    private var previousTimeStamp: Long = 0L
    private var framePeriodMs: Long = 0
    private var bitmap: Bitmap? = null //is used to compress the images

    var quality: Int = 100
    var frameRate: Float = Float.MAX_VALUE
        set(value) {
            if (value > 0) {
                this.framePeriodMs = (1000 / value).toLong()
                log.info("framePeriodMs: $framePeriodMs")
            }
            field = value
        }

    abstract fun screenshot(printer: PrintStream)
    abstract fun getScreenSize(): Size

    fun getTargetSize(): Size = if (rotation % 2 != 0) Size(targetSize.height, targetSize.width) else targetSize
    fun getImageReader(): ImageReader = imageReader

    fun init(out: DisplayOutput) {
        initImageReader()
        clientOutput = out
    }

    /**
     * Initializes the ImageReader for screen capture.
     * 
     * On Android 13+ (SDK 33), ImageReader.newInstance() has a bug where it tries to access
     * Application.getContentResolver() which is null when running from shell (app_process).
     * 
     * This method tries multiple approaches in order:
     * 1. Direct 5-param constructor (standard Android 13+ devices)
     * 2. Unsafe.allocateInstance workaround (custom ROMs like D3 MINI/SUNMI)
     * 3. Standard newInstance() fallback (Android < 13)
     */
    protected fun initImageReader() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Try 5-param constructor first (standard Android 13+ devices)
            try {
                val constructor = ImageReader::class.java.getConstructor(
                    Int::class.java, Int::class.java, Int::class.java, Int::class.java, Long::class.java
                )
                imageReader = constructor.newInstance(
                    getTargetSize().width,
                    getTargetSize().height,
                    PixelFormat.RGBA_8888,
                    2,
                    0L // USAGE_CPU_READ_OFTEN
                )
                return
            } catch (e: NoSuchMethodException) {
                // Constructor not available on this device
            } catch (e: Exception) {
                log.warn("5-param constructor failed: ${e.message}")
            }

            // Fallback: Unsafe.allocateInstance + native init
            // Bypasses Java constructor entirely for custom ROMs without 5-param constructor
            try {
                imageReader = createImageReaderWithUnsafe(
                    getTargetSize().width,
                    getTargetSize().height,
                    PixelFormat.RGBA_8888,
                    2
                )
                return
            } catch (e: Exception) {
                log.warn("Unsafe workaround failed: ${e.message}")
            }
        }
        
        // Standard newInstance (works on Android < 13)
        imageReader = ImageReader.newInstance(
            getTargetSize().width,
            getTargetSize().height,
            PixelFormat.RGBA_8888,
            2
        )
    }

    /**
     * Creates an ImageReader using sun.misc.Unsafe to bypass the constructor.
     * 
     * This approach is needed for custom Android 13+ ROMs (e.g., D3 MINI/SUNMI, Samsung Android 14)
     * where the standard constructors either don't exist or trigger the getContentResolver() bug.
     * 
     * The method:
     * 1. Allocates an ImageReader instance without calling its constructor
     * 2. Manually initializes all required fields via reflection
     * 3. Calls the native initialization method directly
     * 4. Retrieves the Surface from native code
     * 
     * @param width The width of the ImageReader
     * @param height The height of the ImageReader  
     * @param format The pixel format (use PixelFormat.RGBA_8888 = 1)
     * @param maxImages Maximum number of images that can be acquired
     * @return A fully initialized ImageReader instance
     */
    private fun createImageReaderWithUnsafe(width: Int, height: Int, format: Int, maxImages: Int): ImageReader {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafeField = unsafeClass.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null)
        val allocateMethod = unsafeClass.getMethod("allocateInstance", Class::class.java)
        
        // Allocate ImageReader without calling constructor
        val reader = allocateMethod.invoke(unsafe, ImageReader::class.java) as ImageReader
        
        // Initialize required fields
        initializeImageReaderFields(reader, width, height, format, maxImages)
        
        // Call native initialization
        callNativeInit(reader, width, height, format, maxImages)
        
        // Ensure format consistency after native init
        trySetField(reader, "mHardwareBufferFormat", format)
        
        // Get the Surface from native side
        val nativeGetSurfaceMethod = ImageReader::class.java.getDeclaredMethod("nativeGetSurface")
        nativeGetSurfaceMethod.isAccessible = true
        val surface = nativeGetSurfaceMethod.invoke(reader) as android.view.Surface
        setField(reader, "mSurface", surface)
        
        return reader
    }
    
    /**
     * Initializes ImageReader fields via reflection.
     */
    private fun initializeImageReaderFields(reader: ImageReader, width: Int, height: Int, format: Int, maxImages: Int) {
        // Core dimension/format fields
        setField(reader, "mWidth", width)
        setField(reader, "mHeight", height)
        setField(reader, "mFormat", format)
        setField(reader, "mMaxImages", maxImages)
        setField(reader, "mNumPlanes", 1) // RGBA has 1 plane
        
        // Synchronization objects
        setField(reader, "mListenerLock", Object())
        trySetField(reader, "mCloseLock", Object())
        trySetField(reader, "mIsReaderValid", true)
        
        // mAcquiredImages type varies by Android version (List or AtomicInteger)
        try {
            val acquiredImagesField = ImageReader::class.java.getDeclaredField("mAcquiredImages")
            if (java.util.List::class.java.isAssignableFrom(acquiredImagesField.type)) {
                setField(reader, "mAcquiredImages", java.util.ArrayList<Any>())
            } else {
                setField(reader, "mAcquiredImages", java.util.concurrent.atomic.AtomicInteger(0))
            }
        } catch (_: Exception) { }
        
        // Android 13+ specific fields (HardwareBuffer.RGBA_8888 = 1)
        trySetField(reader, "mUsage", 0L)
        trySetField(reader, "mHardwareBufferFormat", format)
        trySetField(reader, "mDataSpace", 0)
    }
    
    /**
     * Calls the appropriate nativeInit method based on the device's signature.
     * 
     * Different Android versions have different nativeInit signatures:
     * - Android 14+ (7-param): (Object, int w, int h, int maxImages, long usage, int hardBufferFormat, int dataSpace)
     * - Android 13 (6-param): (Object, int w, int h, int format, int maxImages, long usage)
     * - Older (5-param): (Object, int w, int h, int format, int maxImages)
     */
    private fun callNativeInit(reader: ImageReader, width: Int, height: Int, format: Int, maxImages: Int) {
        val nativeInitMethod = ImageReader::class.java.declaredMethods.first { it.name == "nativeInit" }
        nativeInitMethod.isAccessible = true
        
        val paramTypes = nativeInitMethod.parameterTypes
        val weakRef = java.lang.ref.WeakReference(reader)
        
        when (paramTypes.size) {
            7 -> {
                // Android 14+: (weakRef, w, h, maxImages, usage, hardBufferFormat, dataSpace)
                nativeInitMethod.invoke(reader, weakRef, width, height, maxImages, 0L, format, 0)
            }
            6 -> {
                // Android 13: parameter order varies
                if (paramTypes[5] == Long::class.javaPrimitiveType) {
                    nativeInitMethod.invoke(reader, weakRef, width, height, format, maxImages, 0L)
                } else {
                    nativeInitMethod.invoke(reader, weakRef, width, height, format, 0L, maxImages)
                }
            }
            5 -> {
                // Older Android
                nativeInitMethod.invoke(reader, weakRef, width, height, format, maxImages)
            }
            else -> throw RuntimeException("Unsupported nativeInit signature with ${paramTypes.size} params")
        }
    }
    
    private fun setField(obj: Any, fieldName: String, value: Any) {
        val field = obj.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        field.set(obj, value)
    }
    
    private fun trySetField(obj: Any, fieldName: String, value: Any) {
        try {
            setField(obj, fieldName, value)
        } catch (e: Exception) {
            log.warn("Could not set field $fieldName: ${e.message}")
        }
    }

    override fun onConnection(socket: LocalSocket) {
        clientOutput = MinicapClientOutput(socket).apply {
            sendBanner(getScreenSize(), getTargetSize(), rotation)
        }
        init(clientOutput)
    }

    override fun onImageAvailable(reader: ImageReader) {
        val image = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            log.error("Failed to acquire image: ${e.message}")
            null
        }

        if (image == null) {
            log.warn("no image available")
            return
        }

        val currentTime = System.currentTimeMillis()
        if (currentTime < previousTimeStamp) {
            // can happen if user sets device time to some point in the past, while streaming
            previousTimeStamp = currentTime
        }

        if (currentTime - previousTimeStamp > framePeriodMs) {
            previousTimeStamp = currentTime
            encode(image, quality, clientOutput.imageBuffer)
            clientOutput.send()
        } else {
            log.warn("skipping frame ($currentTime/$previousTimeStamp)")
        }
        image.close()
    }

    private fun encode(image: Image, q: Int, out: OutputStream) {
        with(image) {
            val planes: Array<Image.Plane> = planes
            val buffer: ByteBuffer = planes[0].buffer
            val pixelStride: Int = planes[0].pixelStride
            val rowStride: Int = planes[0].rowStride
            val rowPadding: Int = rowStride - pixelStride * width
            // createBitmap can be resources consuming
            val bmp = bitmap ?: Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                height,
                Bitmap.Config.ARGB_8888
            ).also { bitmap = it }

            bmp.copyPixelsFromBuffer(buffer)

            //the image need to be cropped
            val croppedBmp = Bitmap.createBitmap(bmp, 0, 0, getTargetSize().width, getTargetSize().height)
            croppedBmp.compress(Bitmap.CompressFormat.JPEG, q, out)
        }
    }
}
