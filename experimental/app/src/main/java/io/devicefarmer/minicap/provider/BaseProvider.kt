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

    protected fun initImageReader() {
        // Android 13 (SDK 33) and newer have a bug where ImageReader.newInstance() tries to access
        // Application.getContentResolver() which is null when running from shell.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Try 5-param constructor first (int, int, int, int, long) - standard Android 13+
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
                log.info("ImageReader created using 5-param constructor")
                return
            } catch (e: NoSuchMethodException) {
                log.warn("5-param constructor not available, trying Unsafe workaround")
            } catch (e: Exception) {
                log.warn("5-param constructor failed: ${e.message}, trying Unsafe workaround")
            }

            // Fallback: Create ImageReader using Unsafe.allocateInstance + native init
            // This bypasses the Java constructor entirely, working around custom Android ROMs, such as the D3 MINI / SUNMI, Android 13
            // that don't have the 5-param constructor but still have the getContentResolver() bug
            try {
                imageReader = createImageReaderWithUnsafe(
                    getTargetSize().width,
                    getTargetSize().height,
                    PixelFormat.RGBA_8888,
                    2
                )
                log.info("ImageReader created using Unsafe workaround")
                return
            } catch (e: Exception) {
                log.warn("Unsafe workaround failed: ${e.message}")
            }
        }
        
        // Final fallback - standard newInstance (works on Android < 13)
        log.info("Using standard ImageReader.newInstance()")
        imageReader = ImageReader.newInstance(
            getTargetSize().width,
            getTargetSize().height,
            PixelFormat.RGBA_8888,
            2
        )
    }

    /**
     * Creates an ImageReader by allocating the object without calling the constructor,
     * then manually initializing its fields and calling native init.
     * This bypasses the problematic getContentResolver() call on custom Android 13 ROMs.
     */
    private fun createImageReaderWithUnsafe(width: Int, height: Int, format: Int, maxImages: Int): ImageReader {
        // Get Unsafe instance
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafeField = unsafeClass.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null)
        val allocateMethod = unsafeClass.getMethod("allocateInstance", Class::class.java)
        
        // Allocate ImageReader without calling constructor
        val reader = allocateMethod.invoke(unsafe, ImageReader::class.java) as ImageReader
        
        // Set required fields via reflection
        setField(reader, "mWidth", width)
        setField(reader, "mHeight", height)
        setField(reader, "mFormat", format)
        setField(reader, "mMaxImages", maxImages)
        setField(reader, "mNumPlanes", 1) // RGBA has 1 plane
        
        // Initialize synchronization lock objects
        setField(reader, "mListenerLock", Object())
        trySetField(reader, "mCloseLock", Object())
        
        // Mark reader as valid
        trySetField(reader, "mIsReaderValid", true)
        
        // Initialize mAcquiredImages - type varies by Android version/ROM
        try {
            val acquiredImagesField = ImageReader::class.java.getDeclaredField("mAcquiredImages")
            if (java.util.List::class.java.isAssignableFrom(acquiredImagesField.type)) {
                setField(reader, "mAcquiredImages", java.util.ArrayList<Any>())
            } else {
                setField(reader, "mAcquiredImages", java.util.concurrent.atomic.AtomicInteger(0))
            }
        } catch (e: Exception) {
            log.warn("Could not set mAcquiredImages: ${e.message}")
        }
        
        // Set usage and format fields (Android 13+)
        trySetField(reader, "mUsage", 0L)
        trySetField(reader, "mHardwareBufferFormat", format)
        trySetField(reader, "mDataSpace", 0)
        
        // Find and call nativeInit
        val nativeInitMethod = findNativeInitMethod()
        nativeInitMethod.isAccessible = true
        
        val paramTypes = nativeInitMethod.parameterTypes
        val weakRef = java.lang.ref.WeakReference(reader)
        
        when (paramTypes.size) {
            7 -> nativeInitMethod.invoke(reader, weakRef, width, height, format, 0L, maxImages, 0)
            6 -> nativeInitMethod.invoke(reader, weakRef, width, height, format, maxImages, 0L)
            5 -> nativeInitMethod.invoke(reader, weakRef, width, height, format, maxImages)
            else -> throw RuntimeException("Unexpected nativeInit signature with ${paramTypes.size} params")
        }
        
        // Get the Surface from native side
        val nativeGetSurfaceMethod = ImageReader::class.java.getDeclaredMethod("nativeGetSurface")
        nativeGetSurfaceMethod.isAccessible = true
        val surface = nativeGetSurfaceMethod.invoke(reader) as android.view.Surface
        setField(reader, "mSurface", surface)
        
        return reader
    }
    
    private fun findNativeInitMethod(): java.lang.reflect.Method {
        val methods = ImageReader::class.java.declaredMethods
        for (method in methods) {
            if (method.name == "nativeInit") {
                return method
            }
        }
        throw NoSuchMethodException("nativeInit not found in ImageReader")
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
