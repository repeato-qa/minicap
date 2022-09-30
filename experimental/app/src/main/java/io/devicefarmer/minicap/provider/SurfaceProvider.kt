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

import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.ImageReader
import android.net.LocalSocket
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Size
import io.devicefarmer.minicap.SimpleServer
import io.devicefarmer.minicap.output.ScreenshotOutput
import io.devicefarmer.minicap.utils.DisplayInfo
import io.devicefarmer.minicap.utils.DisplayManagerGlobal
import io.devicefarmer.minicap.utils.SurfaceControl
import java.io.PrintStream
import java.lang.Exception
import java.util.*
import kotlin.system.exitProcess

/**
 * Provides screen images using [SurfaceControl]. This is pretty similar to the native version
 * of minicap but here it is done at a higher level making things a bit easier.
 */
class SurfaceProvider(displayId: Int, targetSize: Size, orientation: Int) :
    BaseProvider(displayId, targetSize, orientation) {
    constructor(display: Int) : this(display, currentScreenSize(), currentRotation())

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
                                getImageReader().surface.release()
                                //getImageReader().close()
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
     */
    private fun initSurface(l: ImageReader.OnImageAvailableListener) {
        //must be done on the main thread
        // Support  Android 12 (preview),and resolve black screen problem
        val secure =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Build.VERSION.SDK_INT == Build.VERSION_CODES.R && "S" != Build.VERSION.CODENAME
        display = SurfaceControl.createDisplay("minicap", secure)
        //initialise the surface to get the display in the ImageReader
        SurfaceControl.openTransaction()
        try {
            SurfaceControl.setDisplaySurface(display, getImageReader().surface)
            SurfaceControl.setDisplayProjection(
                display,
                0,
                Rect(0, 0, getScreenSize().width, getScreenSize().height),
                Rect(0, 0, getTargetSize().width, getTargetSize().height)
            )
            SurfaceControl.setDisplayLayerStack(display, displayInfo.layerStack)
        } finally {
            SurfaceControl.closeTransaction()
        }
        getImageReader().setOnImageAvailableListener(l, handler)
    }

    private fun initSurface() {
        initSurface(this)
    }
}
