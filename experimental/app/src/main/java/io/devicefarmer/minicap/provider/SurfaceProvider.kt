/*
 * Copyright (C) 2020 Orange
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.devicefarmer.minicap.provider

import android.graphics.Rect
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.net.LocalSocket
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Size
import android.view.Surface
import io.devicefarmer.minicap.output.ScreenshotOutput
import io.devicefarmer.minicap.utils.DisplayInfo
import io.devicefarmer.minicap.utils.DisplayManagerGlobal
import io.devicefarmer.minicap.utils.SurfaceControl
import io.devicefarmer.minicap.utils.SurfaceControl.createDisplay
import io.devicefarmer.minicap.wrappers.ServiceManager
import java.io.PrintStream
import java.util.*
import kotlin.system.exitProcess

/**
 * Provides screen images using [SurfaceControl]. This is pretty similar to the native version
 * of minicap but here it is done at a higher level making things a bit easier.
 */
class SurfaceProvider(displayId: Int, targetSize: Size, orientation: Int) :
    BaseProvider(displayId, targetSize, orientation) {
    constructor(displayId: Int) : this(displayId, currentScreenSize(), currentRotation())

    private var virtualDisplay: VirtualDisplay? = null

    companion object {
        private fun currentScreenSize(): Size {
            return currentDisplayInfo().run {
                Size(this.size.width, this.size.height)
            }
        }

        private fun currentRotation(): Int = currentDisplayInfo().rotation

        private fun currentDisplayInfo(): DisplayInfo {
            return DisplayManagerGlobal.getDisplayInfo(0)
        }
    }

    private val handler: Handler = Handler(Looper.getMainLooper())
    private var display: IBinder? = null
    private var commandProcessor: Thread? = null

    val displayInfo: DisplayInfo = DisplayManagerGlobal.getDisplayInfo(displayId)
    var enabled = true

    override fun getScreenSize(): Size = displayInfo.size


    override fun screenshot(printer: PrintStream) {
        init(ScreenshotOutput(printer))
        initSurface {
            super.onImageAvailable(it)
            exitProcess(0)
        }
    }

    /**
     *
     */
    override fun onConnection(socket: LocalSocket) {
        super.onConnection(socket)

        val uiHandler = Handler(Looper.getMainLooper())
        commandProcessor = Thread(Runnable {
            val scanner = Scanner(socket.inputStream)

            while (scanner.hasNextLine()) {
                val line = scanner.nextLine()
                log.info("Received: $line")

                when (line) {
                    "cmd:stream-disable" -> {
                        if (!enabled) {
                            log.info("Provider is currently disabled- skipping disable command...")
                        } else {
                            log.info("Disabling video stream...")
                            SurfaceControl.openTransaction()
                            try {
                                if (display !== null) {
                                    SurfaceControl.destroyDisplay(display!!)
                                }
                                log.info("Releasing imageReader surface...")
                                getImageReader().surface.release()
                                //getImageReader().close()
                                if(virtualDisplay != null){
                                    log.info("Releasing virtual display...")
                                    virtualDisplay!!.release()
                                }
                                log.info("Done...")
                                this.enabled = false
                            } catch (e: Exception) {
                                log.error(e.toString())
                            } finally {
                                SurfaceControl.closeTransaction()
                            }
                        }

                    }
                    "cmd:stream-enable" -> {
                        if (enabled) {
                            log.info("Provider is currently enabled- skipping enable command...")
                        } else {
                            log.info("Enabling video stream...")
                            initImageReader()
                            initSurface()
                            log.info("Done...")
                            this.enabled = true
                        }
                    }
                    else -> {
                        log.info("Unknown command: " + line)
                    }
                }
            }
        })
        commandProcessor?.start()
        initSurface()
    }

    /**
     * Setup the Surface between the display and an ImageReader so that we can grab the
     * screen.
     * Copied over from scrcpy 2.4: https://github.com/Genymobile/scrcpy/blob/v2.4/server/src/main/java/com/genymobile/scrcpy/ScreenCapture.java
     */
    private fun initSurface(l: ImageReader.OnImageAvailableListener) {
        val surface = getImageReader().surface
        val contentRect = Rect(0, 0, getScreenSize().width, getScreenSize().height)
        val unlockedVideoRect = Rect(0, 0, getTargetSize().width, getTargetSize().height)
        try {
            display = createDisplay()
            setDisplaySurface(
                display!!,
                surface,
                0,
                contentRect,
                unlockedVideoRect,
                displayInfo.layerStack
            )
            log.info("Display: using SurfaceControl API")
        } catch (surfaceControlException: Exception) {
            //val videoRect: Rect = screenInfo.getVideoSize().toRect()
            log.debug("Trying fallback using DisplayManager API...")
            val videoRect = unlockedVideoRect
            try {
                virtualDisplay = ServiceManager.getDisplayManager()
                    .createVirtualDisplay(
                        "minicap",
                        videoRect.width(),
                        videoRect.height(),
                        displayId,
                        surface
                    )
                log.debug("Display: using DisplayManager API")

            } catch (displayManagerException: java.lang.Exception) {
                log.error("Could not create display using SurfaceControl", surfaceControlException)
                log.error("Could not create display using DisplayManager", displayManagerException)
                throw AssertionError("Could not create display")
            }
        }
        getImageReader().setOnImageAvailableListener(l, handler)
    }

    private fun createDisplay(): IBinder? {
        // Since Android 12 (preview), secure displays could not be created with shell permissions anymore.
        // On Android 12 preview, SDK_INT is still R (not S), but CODENAME is "S".
        val secure =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Build.VERSION.SDK_INT == Build.VERSION_CODES.R && "S" != Build.VERSION.CODENAME
        return createDisplay("scrcpy", secure)
    }

    private fun setDisplaySurface(
        display: IBinder,
        surface: Surface,
        orientation: Int,
        deviceRect: Rect,
        displayRect: Rect,
        layerStack: Int
    ) {
        SurfaceControl.openTransaction()
        try {
            SurfaceControl.setDisplaySurface(display, surface)
            SurfaceControl.setDisplayProjection(display, orientation, deviceRect, displayRect)
            SurfaceControl.setDisplayLayerStack(display, layerStack)
        } finally {
            SurfaceControl.closeTransaction()
        }
    }
    private fun initSurface() {
        initSurface(this)
    }
}
